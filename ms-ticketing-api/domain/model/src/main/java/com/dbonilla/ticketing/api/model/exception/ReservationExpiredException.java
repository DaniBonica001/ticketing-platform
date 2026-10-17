package com.dbonilla.ticketing.api.model.exception;

public final class ReservationExpiredException extends TicketingException {

    public ReservationExpiredException(String orderId) {
        super(ErrorCode.RESERVATION_EXPIRED, "The reservation for order " + orderId + " expired");
    }
}
