package com.dbonilla.ticketing.processor.usecase.reserveorder;

import com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException;
import com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException;
import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderStatus;
import com.dbonilla.ticketing.processor.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.processor.model.processing.OrderCommand;
import com.dbonilla.ticketing.processor.model.processing.ProcessingOutcome;
import com.dbonilla.ticketing.processor.model.processing.ReservationSettings;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import com.dbonilla.ticketing.processor.usecase.support.OrderTransitionSupport;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

/**
 * Handles OrderPlaced: T2 (QUEUED -> RESERVED) or, when there are not enough tickets, T3 (REJECTED / SOLD_OUT).
 */
public class ReserveOrderUseCase {

    private final OrderTransitionSupport transitions;
    private final Duration reservationTtl;

    public ReserveOrderUseCase(OrderRepository orderRepository, ReservationSettings reservationSettings,
                               RetryPolicy retryPolicy, Clock clock) {
        this.transitions = new OrderTransitionSupport(orderRepository, retryPolicy, clock);
        this.reservationTtl = reservationSettings.reservationTtl();
    }

    public Mono<ProcessingOutcome> execute(OrderCommand command) {
        return transitions.loadOrder(command.orderId())
                .flatMap(order -> order.isIn(OrderStatus.QUEUED)
                        ? reserve(order, command.traceId())
                        : Mono.just(ProcessingOutcome.ALREADY_PROCESSED));
    }

    private Mono<ProcessingOutcome> reserve(Order order, String traceId) {
        return transitions.apply(now -> order.reserve(now, reservationTtl), traceId)
                .thenReturn(ProcessingOutcome.RESERVED)
                .onErrorResume(InventoryConditionFailedException.class, soldOut -> rejectSoldOut(order, traceId))
                .onErrorResume(OrderStateConflictException.class, duplicate -> Mono.just(ProcessingOutcome.ALREADY_PROCESSED));
    }

    private Mono<ProcessingOutcome> rejectSoldOut(Order order, String traceId) {
        return transitions.apply(order::rejectSoldOut, traceId)
                .thenReturn(ProcessingOutcome.REJECTED_SOLD_OUT);
    }
}
