package com.dbonilla.ticketing.processor.model.exceptions;

/**
 * A failure that may succeed if the operation is retried (throttling, contention, network).
 */
public final class TransientFailureException extends OrderProcessingException {

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
