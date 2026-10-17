package com.dbonilla.ticketing.api.model.exception;

/**
 * Root of every typed business error. Sealed so that entry points can map them exhaustively.
 */
public abstract sealed class TicketingException extends RuntimeException
        permits DomainValidationException, EventNotFoundException, OrderNotFoundException,
        InvalidOrderStateException, ReservationExpiredException, InsufficientInventoryException,
        VersionConflictException, IdempotencyKeyReusedException, ServiceUnavailableException {

    private final ErrorCode code;

    protected TicketingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    protected TicketingException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
