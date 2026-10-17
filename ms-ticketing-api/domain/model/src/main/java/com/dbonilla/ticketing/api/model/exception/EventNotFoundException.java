package com.dbonilla.ticketing.api.model.exception;

public final class EventNotFoundException extends TicketingException {

    public EventNotFoundException(String eventId) {
        super(ErrorCode.EVENT_NOT_FOUND, "Event " + eventId + " was not found");
    }
}
