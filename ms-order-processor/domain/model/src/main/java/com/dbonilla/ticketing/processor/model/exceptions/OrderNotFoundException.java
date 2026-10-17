package com.dbonilla.ticketing.processor.model.exceptions;

public final class OrderNotFoundException extends OrderProcessingException {

    public OrderNotFoundException(String orderId) {
        super("Order " + orderId + " does not exist");
    }
}
