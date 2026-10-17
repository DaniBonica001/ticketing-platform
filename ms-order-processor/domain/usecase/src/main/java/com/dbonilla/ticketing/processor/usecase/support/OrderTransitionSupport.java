package com.dbonilla.ticketing.processor.usecase.support;

import com.dbonilla.ticketing.processor.model.exceptions.OrderNotFoundException;
import com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

/**
 * Behaviour shared by the use cases: reading the order from the source of truth and writing a transition,
 * both retried with bounded exponential backoff only when the failure is transient.
 */
public final class OrderTransitionSupport {

    private final OrderRepository orderRepository;
    private final Clock clock;
    private final Retry transientRetry;

    public OrderTransitionSupport(OrderRepository orderRepository, RetryPolicy retryPolicy, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.transientRetry = Retry.backoff(retryPolicy.maxRetries(), retryPolicy.minBackoff())
                .maxBackoff(retryPolicy.maxBackoff())
                .jitter(retryPolicy.jitter())
                .filter(TransientFailureException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    public Mono<Order> loadOrder(String orderId) {
        return Mono.defer(() -> orderRepository.findById(orderId))
                .retryWhen(transientRetry)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId)));
    }

    /**
     * Builds the transition with a fresh timestamp on every attempt and writes it.
     */
    public Mono<Void> apply(Function<Instant, OrderTransition> transitionFactory, String traceId) {
        return Mono.defer(() -> orderRepository.apply(transitionFactory.apply(clock.instant()), traceId))
                .retryWhen(transientRetry);
    }
}
