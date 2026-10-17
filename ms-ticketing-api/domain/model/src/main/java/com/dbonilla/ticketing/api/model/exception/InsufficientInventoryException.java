package com.dbonilla.ticketing.api.model.exception;

public final class InsufficientInventoryException extends TicketingException {

    public InsufficientInventoryException(String eventId, int requested) {
        super(ErrorCode.INSUFFICIENT_INVENTORY,
                "Event " + eventId + " does not have " + requested + " available tickets");
    }
}
