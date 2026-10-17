package com.dbonilla.ticketing.api.model.order.gateways;

import com.dbonilla.ticketing.api.model.order.ConfirmWriteResult;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderWriteResult;
import com.dbonilla.ticketing.api.model.ordertransition.OrderTransition;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Orders storage. Every write is ONE atomic transaction (order + event + audit).
 */
public interface OrderRepository {

    /** Strongly consistent read; empty when the order does not exist. */
    Mono<Order> findById(String orderId);

    /** T1: creates a QUEUED order and its audit row. */
    Mono<OrderWriteResult> place(Order order, OrderTransition transition);

    /**
     * T8: moves tickets AVAILABLE to COMPLIMENTARY and creates the order. Fails with
     * {@code InsufficientInventoryException} when the event lacks available tickets.
     */
    Mono<OrderWriteResult> issueComplimentary(Order order, OrderTransition transition);

    /** T4: RESERVED to PENDING_CONFIRMATION, moving tickets reserved to pendingConfirmation. */
    Mono<ConfirmWriteResult> confirm(Order next, OrderTransition transition, String callerId, Instant now);
}
