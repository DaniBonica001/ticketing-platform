package com.dbonilla.ticketing.api.model.exception;

public final class VersionConflictException extends TicketingException {

    public VersionConflictException(String eventId, long expectedVersion) {
        super(ErrorCode.VERSION_CONFLICT,
                "Event " + eventId + " is no longer at version " + expectedVersion);
    }
}
