package com.dbonilla.ticketing.processor.model.exceptions;

import com.dbonilla.ticketing.processor.model.order.OrderStatus;

public final class InvalidOrderStateException extends OrderProcessingException {

    public InvalidOrderStateException(String orderId, OrderStatus current, OrderStatus target) {
        super("Order " + orderId + " cannot move from " + current + " to " + target);
    }
}
