package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.IdempotencyKeyReusedException;
import com.dbonilla.ticketing.api.model.exception.InsufficientInventoryException;
import com.dbonilla.ticketing.api.model.order.Caller;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderIdentity;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryCommand;
import com.dbonilla.ticketing.api.usecase.issuecomplimentary.IssueComplimentaryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.dbonilla.ticketing.api.usecase.Fixtures.CLOCK;
import static com.dbonilla.ticketing.api.usecase.Fixtures.EVENT_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueComplimentaryUseCaseTest {

    private static final String KEY = "6a1f0b7e-1111-4c2d-9e3f-0a1b2c3d4e5f";
    private static final IssueComplimentaryCommand COMMAND =
            new IssueComplimentaryCommand(new Caller("admin", true), KEY, EVENT_ID, 2, "vip");

    @Mock
    private EventRepository events;
    @Mock
    private OrderRepository orders;

    private IssueComplimentaryUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new IssueComplimentaryUseCase(events, orders, CLOCK);
    }

    @Test
    void issuesComplimentaryOrderForTheRecipient() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.issueComplimentary(any(), any()))
                .thenAnswer(invocation -> Mono.just(new OrderWriteResult.Created(invocation.getArgument(0))));

        StepVerifier.create(useCase.issue(COMMAND))
                .assertNext(order -> {
                    assertThat(order.orderId()).isEqualTo(OrderIdentity.orderId("admin", KEY));
                    assertThat(order.userId()).isEqualTo("vip");
                    assertThat(order.status()).isEqualTo(OrderStatus.COMPLIMENTARY);
                    assertThat(order.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void replayReturnsTheStoredOrder() {
        Order stored = new Order(OrderIdentity.orderId("admin", KEY), EVENT_ID, "vip", 2, OrderStatus.COMPLIMENTARY,
                null, null, OrderIdentity.requestHash(COMMAND.canonicalPayload()), null, NOW, NOW);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.issueComplimentary(any(), any())).thenReturn(Mono.just(new OrderWriteResult.AlreadyExists(stored)));

        StepVerifier.create(useCase.issue(COMMAND)).expectNext(stored).verifyComplete();
    }

    @Test
    void keyReusedWithOtherPayloadIsRejected() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.issueComplimentary(any(), any()))
                .thenReturn(Mono.just(new OrderWriteResult.AlreadyExists(Fixtures.order(OrderStatus.COMPLIMENTARY, null, null))));

        StepVerifier.create(useCase.issue(COMMAND)).verifyError(IdempotencyKeyReusedException.class);
    }

    @Test
    void propagatesInsufficientInventory() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event()));
        when(orders.issueComplimentary(any(), any()))
                .thenReturn(Mono.error(new InsufficientInventoryException(EVENT_ID, 2)));

        StepVerifier.create(useCase.issue(COMMAND)).verifyError(InsufficientInventoryException.class);
    }

    @Test
    void rejectsUnknownEvent() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.issue(COMMAND)).verifyError(EventNotFoundException.class);
        verify(orders, never()).issueComplimentary(any(), any());
    }
}
