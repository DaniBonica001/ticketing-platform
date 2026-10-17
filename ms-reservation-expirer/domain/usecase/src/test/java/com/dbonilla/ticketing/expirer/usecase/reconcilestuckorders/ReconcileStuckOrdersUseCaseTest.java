package com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders;

import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.execution.TimeBudget;
import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.dbonilla.ticketing.expirer.model.message.OrderMessageType;
import com.dbonilla.ticketing.expirer.model.message.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.expirer.model.order.DeadlineUpdate;
import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.expirer.usecase.shared.ScanSettings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileStuckOrdersUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00Z");
    private static final Duration DEADLINE = Duration.ofSeconds(300);
    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderMessagePublisher publisher = mock(OrderMessagePublisher.class);
    private final ReconcileStuckOrdersUseCase useCase =
            new ReconcileStuckOrdersUseCase(repository, publisher, new ScanSettings(10, 2), DEADLINE);
    private final ExecutionContext context = new ExecutionContext(NOW, "4bf92f3577b34da6a3ce929d0e0e4736",
            TimeBudget.unlimited());

    private static Order order(String id, OrderStatus status) {
        return new Order(id, "event-1", 1, status, NOW.minusSeconds(60));
    }

    private void pageFor(OrderStatus status, Order... orders) {
        when(repository.findOverdue(argThat(query -> query != null && query.status() == status)))
                .thenReturn(Mono.just(OrderPage.last(List.of(orders))));
    }

    @Test
    void republishesStuckOrdersToTheirQueueAndPostponesTheDeadline() {
        Order queued = order("q1", OrderStatus.QUEUED);
        Order pending = order("p1", OrderStatus.PENDING_CONFIRMATION);
        pageFor(OrderStatus.QUEUED, queued);
        pageFor(OrderStatus.PENDING_CONFIRMATION, pending);
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(repository.postponeDeadline(any(), any(), any())).thenReturn(Mono.just(DeadlineUpdate.POSTPONED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.republishedQueued()).isEqualTo(1);
                    assertThat(result.republishedPendingConfirmation()).isEqualTo(1);
                    assertThat(result.failures()).isEmpty();
                })
                .verifyComplete();

        ArgumentCaptor<OrderMessage> messages = ArgumentCaptor.forClass(OrderMessage.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(OrderMessage::orderId, OrderMessage::type)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("q1", OrderMessageType.ORDER_PLACED),
                        org.assertj.core.groups.Tuple.tuple("p1", OrderMessageType.ORDER_CONFIRMATION_REQUESTED));
        verify(repository).postponeDeadline(eq(queued), eq(NOW.plus(DEADLINE)), eq(context));
        verify(repository).postponeDeadline(eq(pending), eq(NOW.plus(DEADLINE)), eq(context));
    }

    @Test
    void countsTheRepublishWhenTheOrderWasProcessedMeanwhile() {
        pageFor(OrderStatus.QUEUED, order("q1", OrderStatus.QUEUED));
        pageFor(OrderStatus.PENDING_CONFIRMATION);
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(repository.postponeDeadline(any(), any(), any())).thenReturn(Mono.just(DeadlineUpdate.STATE_CHANGED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> assertThat(result.republishedQueued()).isEqualTo(1))
                .verifyComplete();
    }

    @Test
    void doesNotPostponeWhenPublishingFails() {
        pageFor(OrderStatus.QUEUED, order("q1", OrderStatus.QUEUED), order("q2", OrderStatus.QUEUED));
        pageFor(OrderStatus.PENDING_CONFIRMATION);
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(publisher.publish(argThat(message -> message != null && message.orderId().equals("q1"))))
                .thenReturn(Mono.error(new IllegalStateException("sqs down")));
        when(repository.postponeDeadline(any(), any(), any())).thenReturn(Mono.just(DeadlineUpdate.POSTPONED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.republishedQueued()).isEqualTo(1);
                    assertThat(result.failures()).singleElement().satisfies(failure -> {
                        assertThat(failure.orderId()).isEqualTo("q1");
                        assertThat(failure.operation()).isEqualTo(ReconcileStuckOrdersUseCase.REPUBLISH_OPERATION);
                        assertThat(failure.status()).isEqualTo(OrderStatus.QUEUED);
                    });
                })
                .verifyComplete();

        verify(repository, never()).postponeDeadline(argThat(o -> o != null && o.orderId().equals("q1")), any(), any());
    }

    @Test
    void aFailedQueryDoesNotStopTheOtherStatus() {
        when(repository.findOverdue(argThat(query -> query != null && query.status() == OrderStatus.QUEUED)))
                .thenReturn(Mono.error(new IllegalStateException("query failed")));
        pageFor(OrderStatus.PENDING_CONFIRMATION, order("p1", OrderStatus.PENDING_CONFIRMATION));
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(repository.postponeDeadline(any(), any(), any())).thenReturn(Mono.just(DeadlineUpdate.POSTPONED));

        StepVerifier.create(useCase.execute(context))
                .assertNext(result -> {
                    assertThat(result.republishedPendingConfirmation()).isEqualTo(1);
                    assertThat(result.failures()).extracting(OrderFailure::operation, OrderFailure::status)
                            .containsExactly(org.assertj.core.groups.Tuple.tuple(
                                    ReconcileStuckOrdersUseCase.QUERY_OPERATION, OrderStatus.QUEUED));
                })
                .verifyComplete();
    }

    @Test
    void ignoresOrdersThatAreNotOverdue() {
        pageFor(OrderStatus.QUEUED, new Order("q1", "event-1", 1, OrderStatus.QUEUED, NOW.plusSeconds(1)));
        pageFor(OrderStatus.PENDING_CONFIRMATION);

        StepVerifier.create(useCase.execute(context))
                .expectNext(ReconciliationResult.EMPTY)
                .verifyComplete();

        verify(publisher, never()).publish(any());
    }

    @Test
    void doesNothingWhenOutOfTime() {
        ExecutionContext outOfTime = new ExecutionContext(NOW, context.traceId(), () -> true);

        StepVerifier.create(useCase.execute(outOfTime))
                .expectNext(ReconciliationResult.EMPTY)
                .verifyComplete();

        verify(repository, never()).findOverdue(any());
    }

    @Test
    void requiresADeadline() {
        ScanSettings settings = new ScanSettings(1, 1);
        assertThatThrownBy(() -> new ReconcileStuckOrdersUseCase(repository, publisher, settings, null))
                .isInstanceOf(NullPointerException.class);
    }
}
