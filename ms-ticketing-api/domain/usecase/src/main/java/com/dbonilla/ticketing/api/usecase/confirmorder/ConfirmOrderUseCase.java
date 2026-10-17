package com.dbonilla.ticketing.api.usecase.confirmorder;

import com.dbonilla.ticketing.api.model.exception.OrderNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ReservationExpiredException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.order.ConfirmDecision;
import com.dbonilla.ticketing.api.model.order.ConfirmWriteResult;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderPolicy;
import com.dbonilla.ticketing.api.model.order.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.api.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * T4: the owner confirms a RESERVED order before its deadline. Idempotent for the same payment reference.
 */
public class ConfirmOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderMessagePublisher publisher;
    private final Clock clock;
    private final OrderPolicy policy;

    public ConfirmOrderUseCase(OrderRepository orderRepository, OrderMessagePublisher publisher,
                               Clock clock, OrderPolicy policy) {
        this.orderRepository = orderRepository;
        this.publisher = publisher;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<Order> confirm(ConfirmOrderCommand command) {
        return orderRepository.findById(command.orderId())
                .filter(order -> command.caller().owns(order))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(command.orderId())))
                .flatMap(order -> decide(order, command, clock.instant().truncatedTo(ChronoUnit.MILLIS)));
    }

    private Mono<Order> decide(Order order, ConfirmOrderCommand command, Instant now) {
        return switch (order.confirm(command.paymentReference(), now, policy)) {
            case ConfirmDecision.AlreadyConfirmed(Order current) -> Mono.just(current);
            case ConfirmDecision.Apply(Order next) -> orderRepository
                    .confirm(next, OrderTransition.between(order, next), command.caller().userId(), now)
                    .flatMap(result -> afterWrite(result, command, now));
        };
    }

    private Mono<Order> afterWrite(ConfirmWriteResult result, ConfirmOrderCommand command, Instant now) {
        return switch (result) {
            case ConfirmWriteResult.Confirmed(Order saved) -> enqueue(saved);
            case ConfirmWriteResult.Conflict(Order current) -> resolveConflict(current, command, now);
        };
    }

    /**
     * The conditional write lost a race (for example against the expirer or a concurrent confirmation):
     * re-evaluate the decision against the stored state.
     */
    private Mono<Order> resolveConflict(Order current, ConfirmOrderCommand command, Instant now) {
        if (current == null || !command.caller().owns(current)) {
            return Mono.error(new OrderNotFoundException(command.orderId()));
        }
        return switch (current.confirm(command.paymentReference(), now, policy)) {
            case ConfirmDecision.AlreadyConfirmed(Order confirmed) -> Mono.just(confirmed);
            case ConfirmDecision.Apply ignored -> Mono.error(new ReservationExpiredException(command.orderId()));
        };
    }

    /**
     * Same dual-write policy as T1: only when the queue stays unavailable after the retries is the failure
     * absorbed, because the expirer re-publishes stuck confirmations (ADR-0005, R2).
     */
    private Mono<Order> enqueue(Order order) {
        return publisher.publishConfirmationRequested(order)
                .onErrorResume(ServiceUnavailableException.class, unavailable -> Mono.empty())
                .thenReturn(order);
    }
}
