package com.dbonilla.ticketing.api.model.exception;

import com.dbonilla.ticketing.api.model.order.OrderStatus;

public final class InvalidOrderStateException extends TicketingException {

    public InvalidOrderStateException(String orderId, OrderStatus current, OrderStatus target) {
        super(ErrorCode.INVALID_ORDER_STATE,
                "Order " + orderId + " cannot move from " + current + " to " + target);
    }
}
