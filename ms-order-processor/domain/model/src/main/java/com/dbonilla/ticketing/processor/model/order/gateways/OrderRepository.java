package com.dbonilla.ticketing.processor.model.order.gateways;

import com.dbonilla.ticketing.processor.model.order.Order;
import com.dbonilla.ticketing.processor.model.order.OrderTransition;
import reactor.core.publisher.Mono;

public interface OrderRepository {

    /**
     * Strongly consistent read of an order. Empty when the order does not exist.
     */
    Mono<Order> findById(String orderId);

    /**
     * Applies the transition as ONE atomic write (order + inventory + audit).
     * Errors: {@link com.dbonilla.ticketing.processor.model.exceptions.OrderStateConflictException} when the order is
     * no longer in {@code transition.from()}, {@link com.dbonilla.ticketing.processor.model.exceptions.InventoryConditionFailedException}
     * when the inventory does not have enough tickets, {@link com.dbonilla.ticketing.processor.model.exceptions.TransientFailureException}
     * for errors that may succeed when retried.
     */
    Mono<Void> apply(OrderTransition transition, String traceId);
}
