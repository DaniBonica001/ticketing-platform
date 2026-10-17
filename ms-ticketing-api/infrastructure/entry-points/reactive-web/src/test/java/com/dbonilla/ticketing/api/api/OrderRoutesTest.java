package com.dbonilla.ticketing.api.api;

import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.IdempotencyKeyReusedException;
import com.dbonilla.ticketing.api.model.exception.InsufficientInventoryException;
import com.dbonilla.ticketing.api.model.exception.InvalidOrderStateException;
import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.RejectionReason;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderCommand;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryCommand;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderCommand;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRoutesTest extends WebTestBase {

    private static final String PLACE_BODY = "{\"eventId\":\"" + EVENT_ID + "\",\"quantity\":2}";

    @Test
    void userPlacesOrderAndGets202WithLocation() {
        PlaceOrderCommand expected = new PlaceOrderCommand(new Caller(USER_ID, false), KEY, EVENT_ID, 2);
        when(placeOrder.place(expected)).thenReturn(Mono.just(order(OrderStatus.QUEUED)));

        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/v1/orders/" + ORDER_ID)
                .expectBody()
                .jsonPath("$.status").isEqualTo("QUEUED")
                .jsonPath("$.expiresAt").isEmpty()
                .jsonPath("$.userId").doesNotExist();
    }

    @Test
    void placeRequiresAValidIdempotencyKey() {
        user().post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("Idempotency-Key");
        user().post().uri("/api/v1/orders").header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isBadRequest();
        verify(placeOrder, never()).place(any());
    }

    @Test
    void placeValidatesQuantityAndEventId() {
        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"eventId\":\"x\",\"quantity\":11}").exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("eventId")
                .jsonPath("$.errors[1].field").isEqualTo("quantity");
    }

    @Test
    void placeRequiresUserScope() {
        admin().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isForbidden();
        client.post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void placeReportsBusinessErrors() {
        when(placeOrder.place(any())).thenReturn(Mono.error(new IdempotencyKeyReusedException()));
        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");

        when(placeOrder.place(any())).thenReturn(Mono.error(new EventNotFoundException(EVENT_ID)));
        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(PLACE_BODY).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void bodiesAbove16KbAreRejected() {
        String big = "{\"eventId\":\"" + "a".repeat(17 * 1024) + "\",\"quantity\":1}";

        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(big).exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void chunkedBodiesAbove16KbAreRejectedByTheCodec() {
        String big = "{\"eventId\":\"" + "a".repeat(17 * 1024) + "\",\"quantity\":1}";

        user().post().uri("/api/v1/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(big.substring(0, 9000), big.substring(9000)), String.class)
                .exchange()
                .expectStatus().isEqualTo(413);
    }

    @Test
    void ownerGetsOrderWithExpiry() {
        when(getOrder.get(ORDER_ID, new Caller(USER_ID, false))).thenReturn(Mono.just(order(OrderStatus.RESERVED)));

        user().get().uri("/api/v1/orders/" + ORDER_ID).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.expiresAt").isEqualTo("2026-10-16T10:10:00.123Z");
    }

    @Test
    void adminGetsAnyOrderAndRejectionReasonIsShown() {
        Order rejected = new Order(ORDER_ID, EVENT_ID, USER_ID, 2, OrderStatus.REJECTED, RejectionReason.SOLD_OUT,
                null, "h", null, NOW, NOW);
        when(getOrder.get(ORDER_ID, new Caller("admin-1", true))).thenReturn(Mono.just(rejected));

        admin().get().uri("/api/v1/orders/" + ORDER_ID).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.reason").isEqualTo("SOLD_OUT");
    }

    @Test
    void foreignOrderIsNotFound() {
        when(getOrder.get(eq(ORDER_ID), any())).thenReturn(Mono.error(new OrderNotFoundException(ORDER_ID)));

        as("intruder", "ticketing:user").get().uri("/api/v1/orders/" + ORDER_ID).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void ownerConfirmsReservedOrder() {
        ConfirmOrderCommand expected = new ConfirmOrderCommand(new Caller(USER_ID, false), ORDER_ID, "PAY-123");
        when(confirmOrder.confirm(expected)).thenReturn(Mono.just(order(OrderStatus.PENDING_CONFIRMATION)));

        user().post().uri("/api/v1/orders/" + ORDER_ID + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paymentReference\":\"PAY-123\"}").exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void confirmValidatesPaymentReference() {
        user().post().uri("/api/v1/orders/" + ORDER_ID + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paymentReference\":\"P$\"}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("paymentReference");
    }

    @Test
    void confirmConflictsAreReported() {
        when(confirmOrder.confirm(any())).thenReturn(Mono.error(new ReservationExpiredException(ORDER_ID)));
        user().post().uri("/api/v1/orders/" + ORDER_ID + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paymentReference\":\"PAY-123\"}").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("RESERVATION_EXPIRED");

        when(confirmOrder.confirm(any())).thenReturn(Mono.error(
                new InvalidOrderStateException(ORDER_ID, OrderStatus.QUEUED, OrderStatus.PENDING_CONFIRMATION)));
        user().post().uri("/api/v1/orders/" + ORDER_ID + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"paymentReference\":\"PAY-123\"}").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_ORDER_STATE");
    }

    @Test
    void historyListsTransitions() {
        Order order = order(OrderStatus.QUEUED);
        when(orderHistory.history(ORDER_ID, new Caller(USER_ID, false)))
                .thenReturn(Flux.just(OrderTransition.created(order),
                        new OrderTransition(ORDER_ID, EVENT_ID, OrderStatus.QUEUED, OrderStatus.REJECTED, 2,
                                RejectionReason.SOLD_OUT, "order-processor", NOW.plusSeconds(1))));

        user().get().uri("/api/v1/orders/" + ORDER_ID + "/history").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].fromStatus").isEmpty()
                .jsonPath("$[0].toStatus").isEqualTo("QUEUED")
                .jsonPath("$[1].reason").isEqualTo("SOLD_OUT")
                .jsonPath("$[1].actor").isEqualTo("order-processor");
    }

    @Test
    void adminIssuesComplimentaryTickets() {
        IssueComplimentaryCommand expected =
                new IssueComplimentaryCommand(new Caller("admin-1", true), KEY, EVENT_ID, 2, "vip");
        when(complimentary.issue(expected)).thenReturn(Mono.just(order(OrderStatus.COMPLIMENTARY)));

        admin().post().uri("/api/v1/events/" + EVENT_ID + "/complimentary").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":2,\"recipientUserId\":\"vip\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/orders/" + ORDER_ID)
                .expectBody().jsonPath("$.status").isEqualTo("COMPLIMENTARY");
    }

    @Test
    void complimentaryWithoutInventoryIsConflict() {
        when(complimentary.issue(any())).thenReturn(Mono.error(new InsufficientInventoryException(EVENT_ID, 2)));

        admin().post().uri("/api/v1/events/" + EVENT_ID + "/complimentary").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":2,\"recipientUserId\":\"vip\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("INSUFFICIENT_INVENTORY");
    }

    @Test
    void complimentaryRequiresAdmin() {
        user().post().uri("/api/v1/events/" + EVENT_ID + "/complimentary").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":2,\"recipientUserId\":\"vip\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void unknownRoutesAreDeniedByDefault() {
        user().delete().uri("/api/v1/orders/" + ORDER_ID).exchange().expectStatus().isForbidden();
        client.get().uri("/internal").exchange().expectStatus().isUnauthorized();
    }
}
