package com.dbonilla.ticketing.api.model.exception;

public final class DomainValidationException extends TicketingException {

    private final String field;

    public DomainValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
