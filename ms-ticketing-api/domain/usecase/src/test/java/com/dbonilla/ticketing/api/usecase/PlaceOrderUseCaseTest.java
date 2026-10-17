package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.IdempotencyKeyReusedException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderIdentity;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderCommand;
import com.dbonilla.ticketing.api.usecase.placeorder.PlaceOrderUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.dbonilla.ticketing.api.usecase.Fixtures.CLOCK;
import static com.dbonilla.ticketing.api.usecase.Fixtures.EVENT_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.NOW;
import static com.dbonilla.ticketing.api.usecase.Fixtures.POLICY;
import static com.dbonilla.ticketing.api.usecase.Fixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaceOrderUseCaseTest {

    private static final String KEY = "3b241101-e2bb-4255-8caf-4136c566a962";
    private static final PlaceOrderCommand COMMAND = new PlaceOrderCommand(new Caller(USER_ID, false), KEY, EVENT_ID, 2);

    @Mock
    private EventRepository events;
    @Mock
    private OrderRepository orders;
    @Mock
    private OrderMessagePublisher publisher;

    private PlaceOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(events, orders, publisher, CLOCK, POLICY);
    }

    @Test
    void createsQueuedOrderWithDeterministicIdAndPublishesIt() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.place(any(), any())).thenAnswer(invocation -> Mono.just(new OrderWriteResult.Created(invocation.getArgument(0))));
        when(publisher.publishOrderPlaced(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(order -> {
                    assertThat(order.orderId()).isEqualTo(OrderIdentity.orderId(USER_ID, KEY));
                    assertThat(order.status()).isEqualTo(OrderStatus.QUEUED);
                    assertThat(order.requestHash()).isEqualTo(OrderIdentity.requestHash(EVENT_ID + ":2"));
                    assertThat(order.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
        ArgumentCaptor<OrderTransition> transition = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orders).place(any(), transition.capture());
        assertThat(transition.getValue().fromStatus()).isNull();
        assertThat(transition.getValue().toStatus()).isEqualTo(OrderStatus.QUEUED);
        verify(publisher).publishOrderPlaced(any());
    }

    @Test
    void stillAcceptsTheOrderWhenPublishingFails() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.place(any(), any())).thenAnswer(invocation -> Mono.just(new OrderWriteResult.Created(invocation.getArgument(0))));
        when(publisher.publishOrderPlaced(any())).thenReturn(Mono.error(new IllegalStateException("sqs down")));

        StepVerifier.create(useCase.place(COMMAND))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.QUEUED))
                .verifyComplete();
    }

    @Test
    void replayWithSamePayloadReturnsExistingOrderWithoutPublishing() {
        Order existing = new Order(OrderIdentity.orderId(USER_ID, KEY), EVENT_ID, USER_ID, 2, OrderStatus.RESERVED,
                null, NOW, OrderIdentity.requestHash(EVENT_ID + ":2"), null, NOW, NOW);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.place(any(), any())).thenReturn(Mono.just(new OrderWriteResult.AlreadyExists(existing)));

        StepVerifier.create(useCase.place(COMMAND)).expectNext(existing).verifyComplete();
        verify(publisher, never()).publishOrderPlaced(any());
    }

    @Test
    void replayWithOtherPayloadIsRejected() {
        Order existing = Fixtures.order(OrderStatus.QUEUED, NOW, null);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.place(any(), any())).thenReturn(Mono.just(new OrderWriteResult.AlreadyExists(existing)));

        StepVerifier.create(useCase.place(COMMAND)).verifyError(IdempotencyKeyReusedException.class);
    }

    @Test
    void rejectsUnknownEventWithoutWriting() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.place(COMMAND)).verifyError(EventNotFoundException.class);
        verify(orders, never()).place(any(), any());
    }

    @Test
    void propagatesStorageUnavailability() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.place(any(), any())).thenReturn(Mono.error(new ServiceUnavailableException("DynamoDB", null)));

        StepVerifier.create(useCase.place(COMMAND)).verifyError(ServiceUnavailableException.class);
        verify(publisher, never()).publishOrderPlaced(any());
    }
}
