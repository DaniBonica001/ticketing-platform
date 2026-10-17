package com.dbonilla.ticketing.processor.usecase.processpayment;

import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.MissingPaymentReferenceException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import com.dbonilla.ticketing.processor.model.order.RejectionReason;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import com.dbonilla.ticketing.processor.model.payment.gateways.PaymentGateway;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessPaymentUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:05:00.000Z");
    private static final RetryPolicy FAST_RETRY = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 0.5);
    private static final OrderCommand COMMAND = new OrderCommand("msg-1", "order-1", "event-1", "trace-1");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentGateway paymentGateway;

    private ProcessPaymentUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentUseCase(orderRepository, paymentGateway, FAST_RETRY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order pending(String paymentReference) {
        return new Order("order-1", "event-1", "user-1", 2, OrderStatus.PENDING_CONFIRMATION, null,
                NOW.plusSeconds(300), paymentReference);
    }

    private OrderTransition capturedTransition() {
        ArgumentCaptor<OrderTransition> captor = ArgumentCaptor.forClass(OrderTransition.class);
        verify(orderRepository).apply(captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void marksOrderSoldWhenPaymentIsApproved() {
        // Arrange
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("REF-1")));
        when(paymentGateway.verify("REF-1")).thenReturn(Mono.just(PaymentStatus.APPROVED));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        assertThat(capturedTransition().to()).isEqualTo(OrderStatus.SOLD);
    }

    @Test
    void rejectsOrderWhenPaymentIsDeclined() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("DECLINED-1")));
        when(paymentGateway.verify("DECLINED-1")).thenReturn(Mono.just(PaymentStatus.DECLINED));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.REJECTED_PAYMENT_DECLINED)
                .verifyComplete();
        OrderTransition transition = capturedTransition();
        assertThat(transition.to()).isEqualTo(OrderStatus.REJECTED);
        assertThat(transition.reason()).isEqualTo(RejectionReason.PAYMENT_DECLINED);
    }

    @Test
    void acknowledgesOrdersThatAreNoLongerPending() {
        Order sold = new Order("order-1", "event-1", "user-1", 2, OrderStatus.SOLD, null, null, "REF-1");
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(sold));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
        verify(paymentGateway, never()).verify(anyString());
    }

    @Test
    void treatsOrderConditionFailureAsDuplicate() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("REF-1")));
        when(paymentGateway.verify("REF-1")).thenReturn(Mono.just(PaymentStatus.APPROVED));
        when(orderRepository.apply(any(), any()))
                .thenReturn(Mono.error(new OrderStateConflictException("order-1", OrderStatus.PENDING_CONFIRMATION)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.ALREADY_PROCESSED)
                .verifyComplete();
    }

    @Test
    void propagatesGatewayUnavailabilitySoTheMessageIsKept() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("REF-1")));
        when(paymentGateway.verify("REF-1"))
                .thenReturn(Mono.error(new PaymentGatewayUnavailableException("circuit open", null)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(PaymentGatewayUnavailableException.class)
                .verify();
        verify(orderRepository, never()).apply(any(), any());
    }

    @Test
    void failsWhenThePendingOrderHasNoPaymentReference() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending(" ")));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(MissingPaymentReferenceException.class)
                .verify();
    }

    @Test
    void failsWhenThePendingOrderHasNullPaymentReference() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending(null)));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(MissingPaymentReferenceException.class)
                .verify();
    }

    @Test
    void propagatesInventoryInconsistencies() {
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("REF-1")));
        when(paymentGateway.verify("REF-1")).thenReturn(Mono.just(PaymentStatus.APPROVED));
        when(orderRepository.apply(any(), any()))
                .thenReturn(Mono.error(new InventoryConditionFailedException("event-1")));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectError(InventoryConditionFailedException.class)
                .verify();
    }

    @Test
    void retriesTransientWriteFailures() {
        AtomicInteger attempts = new AtomicInteger();
        when(orderRepository.findById("order-1")).thenReturn(Mono.just(pending("REF-1")));
        when(paymentGateway.verify("REF-1")).thenReturn(Mono.just(PaymentStatus.APPROVED));
        when(orderRepository.apply(any(), any())).thenReturn(Mono.defer(() -> attempts.incrementAndGet() == 1
                ? Mono.error(new TransientFailureException("conflict", null))
                : Mono.empty()));

        StepVerifier.create(useCase.execute(COMMAND))
                .expectNext(ProcessingOutcome.SOLD)
                .verifyComplete();
        assertThat(attempts).hasValue(2);
    }
}
