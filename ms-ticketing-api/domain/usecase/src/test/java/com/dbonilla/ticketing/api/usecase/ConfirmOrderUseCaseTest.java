package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.exception.InvalidOrderStateException;
import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.ConfirmWriteResult;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderCommand;
import com.dbonilla.ticketing.api.usecase.confirmorder.ConfirmOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.dbonilla.ticketing.api.usecase.Fixtures.CLOCK;
import static com.dbonilla.ticketing.api.usecase.Fixtures.NOW;
import static com.dbonilla.ticketing.api.usecase.Fixtures.ORDER_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.POLICY;
import static com.dbonilla.ticketing.api.usecase.Fixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmOrderUseCaseTest {

    private static final ConfirmOrderCommand COMMAND =
            new ConfirmOrderCommand(new Caller(USER_ID, false), ORDER_ID, "PAY-123");
    private static final Order RESERVED = Fixtures.order(OrderStatus.RESERVED, NOW.plusSeconds(120), null);

    @Mock
    private OrderRepository orders;
    @Mock
    private OrderMessagePublisher publisher;

    private ConfirmOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ConfirmOrderUseCase(orders, publisher, CLOCK, POLICY);
    }

    @Test
    void confirmsReservedOrderAndRequestsPaymentValidation() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), eq(USER_ID), eq(NOW)))
                .thenAnswer(invocation -> Mono.just(new ConfirmWriteResult.Confirmed(invocation.getArgument(0))));
        when(publisher.publishConfirmationRequested(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.confirm(COMMAND))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
                    assertThat(order.paymentReference()).isEqualTo("PAY-123");
                })
                .verifyComplete();
        ArgumentCaptor<OrderTransition> transition = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orders).confirm(any(), transition.capture(), eq(USER_ID), eq(NOW));
        assertThat(transition.getValue().fromStatus()).isEqualTo(OrderStatus.RESERVED);
        verify(publisher).publishConfirmationRequested(any());
    }

    @Test
    void stillAcceptsWhenThePublisherIsUnavailableAfterRetries() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), eq(USER_ID), eq(NOW)))
                .thenAnswer(invocation -> Mono.just(new ConfirmWriteResult.Confirmed(invocation.getArgument(0))));
        when(publisher.publishConfirmationRequested(any()))
                .thenReturn(Mono.error(new ServiceUnavailableException("SQS", new IllegalStateException("down"))));

        StepVerifier.create(useCase.confirm(COMMAND))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION))
                .verifyComplete();
    }

    @Test
    void propagatesNonTransientPublishingFailures() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), eq(USER_ID), eq(NOW)))
                .thenAnswer(invocation -> Mono.just(new ConfirmWriteResult.Confirmed(invocation.getArgument(0))));
        when(publisher.publishConfirmationRequested(any())).thenReturn(Mono.error(new IllegalStateException("denied")));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyErrorMessage("denied");
    }

    @Test
    void repeatedConfirmationIsIdempotent() {
        Order pending = Fixtures.order(OrderStatus.PENDING_CONFIRMATION, NOW.plusSeconds(120), "PAY-123");
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(pending));

        StepVerifier.create(useCase.confirm(COMMAND)).expectNext(pending).verifyComplete();
        verify(orders, never()).confirm(any(), any(), any(), any());
        verify(publisher, never()).publishConfirmationRequested(any());
    }

    @Test
    void otherUsersAndAdminsCannotConfirm() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        ConfirmOrderCommand byAdmin = new ConfirmOrderCommand(new Caller("admin", true), ORDER_ID, "PAY-123");

        StepVerifier.create(useCase.confirm(byAdmin)).verifyError(OrderNotFoundException.class);
    }

    @Test
    void missingOrderIsNotFound() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(OrderNotFoundException.class);
    }

    @Test
    void expiredReservationIsRejected() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(Fixtures.order(OrderStatus.RESERVED, NOW, null)));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(ReservationExpiredException.class);
    }

    @Test
    void lostRaceAgainstSameConfirmationIsIdempotent() {
        Order pending = Fixtures.order(OrderStatus.PENDING_CONFIRMATION, NOW.plusSeconds(300), "PAY-123");
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), any(), any())).thenReturn(Mono.just(new ConfirmWriteResult.Conflict(pending)));

        StepVerifier.create(useCase.confirm(COMMAND)).expectNext(pending).verifyComplete();
        verify(publisher, never()).publishConfirmationRequested(any());
    }

    @Test
    void lostRaceAgainstExpirerIsInvalidState() {
        Order expired = Fixtures.order(OrderStatus.EXPIRED, null, null);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), any(), any())).thenReturn(Mono.just(new ConfirmWriteResult.Conflict(expired)));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(InvalidOrderStateException.class);
    }

    @Test
    void conditionFailureOnStillReservedOrderMeansDeadlineReached() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), any(), any())).thenReturn(Mono.just(new ConfirmWriteResult.Conflict(RESERVED)));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(ReservationExpiredException.class);
    }

    @Test
    void conflictOnVanishedOrderIsNotFound() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), any(), any())).thenReturn(Mono.just(new ConfirmWriteResult.Conflict(null)));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(OrderNotFoundException.class);
    }

    @Test
    void conflictOnOrderOfOtherOwnerIsNotFound() {
        Order foreign = new Order(ORDER_ID, RESERVED.eventId(), "other", 2, OrderStatus.RESERVED, null,
                NOW.plusSeconds(60), "hash", null, NOW, NOW);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(RESERVED));
        when(orders.confirm(any(), any(), any(), any())).thenReturn(Mono.just(new ConfirmWriteResult.Conflict(foreign)));

        StepVerifier.create(useCase.confirm(COMMAND)).verifyError(OrderNotFoundException.class);
    }
}
