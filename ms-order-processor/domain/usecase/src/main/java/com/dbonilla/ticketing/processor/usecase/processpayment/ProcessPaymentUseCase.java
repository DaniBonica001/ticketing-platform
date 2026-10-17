package com.dbonilla.ticketing.processor.usecase.processpayment;

import com.dbonilla.ticketing.processor.model.exceptions.MissingPaymentReferenceException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import com.dbonilla.ticketing.processor.model.payment.gateways.PaymentGateway;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import com.dbonilla.ticketing.processor.usecase.support.OrderTransitionSupport;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Handles OrderConfirmationRequested: verifies the payment and applies T5 (SOLD) or T6 (REJECTED / PAYMENT_DECLINED).
 */
public class ProcessPaymentUseCase {

    private final OrderTransitionSupport transitions;
    private final PaymentGateway paymentGateway;

    public ProcessPaymentUseCase(OrderRepository orderRepository, PaymentGateway paymentGateway,
                                 RetryPolicy retryPolicy, Clock clock) {
        this.transitions = new OrderTransitionSupport(orderRepository, retryPolicy, clock);
        this.paymentGateway = paymentGateway;
    }

    public Mono<ProcessingOutcome> execute(OrderCommand command) {
        return transitions.loadOrder(command.orderId())
                .flatMap(order -> order.isIn(OrderStatus.PENDING_CONFIRMATION)
                        ? settle(order, command.traceId())
                        : Mono.just(ProcessingOutcome.ALREADY_PROCESSED));
    }

    private Mono<ProcessingOutcome> settle(Order order, String traceId) {
        return requirePaymentReference(order)
                .flatMap(paymentGateway::verify)
                .flatMap(status -> applyPaymentResult(order, status, traceId))
                .onErrorResume(OrderStateConflictException.class, duplicate -> Mono.just(ProcessingOutcome.ALREADY_PROCESSED));
    }

    private Mono<ProcessingOutcome> applyPaymentResult(Order order, PaymentStatus status, String traceId) {
        return switch (status) {
            case APPROVED -> transitions.apply(order::markSold, traceId)
                    .thenReturn(ProcessingOutcome.SOLD);
            case DECLINED -> transitions.apply(order::rejectPaymentDeclined, traceId)
                    .thenReturn(ProcessingOutcome.REJECTED_PAYMENT_DECLINED);
        };
    }

    private static Mono<String> requirePaymentReference(Order order) {
        String reference = order.paymentReference();
        return reference == null || reference.isBlank()
                ? Mono.error(new MissingPaymentReferenceException(order.orderId()))
                : Mono.just(reference);
    }
}
