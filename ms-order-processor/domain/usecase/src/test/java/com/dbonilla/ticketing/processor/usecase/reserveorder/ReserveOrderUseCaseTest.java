package com.dbonilla.ticketing.processor.usecase.reserveorder;

import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderNotFoundException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import com.dbonilla.ticketing.processor.model.order.RejectionReason;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.model.processing.ReservationSettings;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReserveOrderUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00.000Z");
    private static final RetryPolicy FAST_RETRY = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.5);
    private static final OrderCommand COMMAND = new OrderCommand("msg-1", "order-1", "event-1", "trace-1");

    @Mock
    private OrderRepository orderRepository;

    private ReserveOrderUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReserveOrderUseCase(orderRepository, new ReservationSettings(Duration.ofMinutes(10)),
                FAST_RETRY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order(OrderStatus status) {
        return new Order("order-1", "event-1", "user-1", 3, status, null, null, null);
    }

    @Test
    void reservesQueuedOrderWithQuantityFromTheSourceOfTruth() {
        // Arrange
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), eq("trace-1"))).thenReturn(Mono.empty());

        // Act
        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.RESERVED)
                .verifyComplete();

        // Assert
        ArgumentCaptor<OrderTransition> captor = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository).apply(captor.capture(), eq("trace-1"));
        assertThat(captor.getValue().to()).isEqualTo(OrderStatus.RESERVED);
        assertThat(captor.getValue().quantity()).isEqualTo(3);
        assertThat(captor.getValue().deadlineAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void rejectsAsSoldOutWhenInventoryConditionFails() {
        // Arrange
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any()))
                .thenReturn(Mono.error(new InventoryConditionFailedException("event-1")))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.REJECTED_SOLD_OUT)
                .verifyComplete();
        ArgumentCaptor<OrderTransition> captor = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository, times(2)).apply(captor.capture(), any());
        OrderTransition rejection = captor.getAllValues().get(1);
        assertThat(rejection.to()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejection.reason()).isEqualTo(RejectionReason.SOLD_OUT);
    }

    @Test
    void acknowledgesAlreadyProcessedOrderWithoutWriting() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.RESERVED)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
        verify(orderRepository, never()).apply(any(), any());
    }

    @Test
    void treatsOrderConditionFailureAsDuplicate() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any()))
                .thenReturn(Mono.error(new OrderStateConflictException("order-1", OrderStatus.QUEUED)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
    }

    @Test
    void treatsConflictWhileRejectingAsDuplicate() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any()))
                .thenReturn(Mono.error(new InventoryConditionFailedException("event-1")))
                .thenReturn(Mono.error(new OrderStateConflictException("order-1", OrderStatus.QUEUED)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
    }

    @Test
    void retriesTransientFailuresAndThenSucceeds() {
        // Arrange
        AtomicInteger attempts = new AtomicInteger();
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() < 3
                ? Mono.error(new TransientFailureException("conflict", null))
                : Mono.empty()));

        // Act & Assert
        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.RESERVED)
                .verifyComplete();
        assertThat(attempts).hasValue(3);
    }

    @Test
    void propagatesTransientFailureWhenRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new TransientFailureException("throttled", null));
        }));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(TransientFailureException.class)
                .verify();
        assertThat(attempts).hasValue(4);
    }

    @Test
    void neverRetriesBusinessFailures() {
        AtomicInteger attempts = new AtomicInteger();
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(order(OrderStatus.QUEUED)));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new OrderStateConflictException("order-1", OrderStatus.QUEUED));
        }));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retriesTransientReadFailures() {
        AtomicInteger reads = new AtomicInteger();
        when(orderRepository.findById("order-1")).thenReturn(Mono.defer(() -> reads.incrementAndGet() == 1
                ? Mono.error(new TransientFailureException("timeout", null))
                : Mono.just(order(OrderStatus.SOLD))));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
        assertThat(reads).hasValue(2);
    }

    @Test
    void failsWhenTheOrderDoesNotExist() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(OrderNotFoundException.class)
                .verify();
    }
}
