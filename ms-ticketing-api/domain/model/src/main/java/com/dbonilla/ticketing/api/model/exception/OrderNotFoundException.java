package com.dbonilla.ticketing.api.model.exception;

public final class OrderNotFoundException extends TicketingException {

    public OrderNotFoundException(String orderId) {
        super(ErrorCode.ORDER_NOT_FOUND, "Order " + orderId + " was not found");
    }
}
