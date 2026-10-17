package com.dbonilla.ticketing.api.model.exception;

public final class ServiceUnavailableException extends TicketingException {

    public ServiceUnavailableException(String dependency, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, dependency + " is unavailable", cause);
    }
}
