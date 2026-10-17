package com.dbonilla.ticketing.processor.model.exceptions;

import com.dbonilla.ticketing.processor.model.order.OrderStatus;

/**
 * The order was no longer in the expected state when the transition was written
 * (already processed by another delivery of the same message).
 */
public final class OrderStateConflictException extends OrderProcessingException {

    public OrderStateConflictException(String orderId, OrderStatus expected) {
        super("Order " + orderId + " is no longer " + expected);
    }
}
