package com.dbonilla.ticketing.processor.sqs.listener.message;

/**
 * The message does not satisfy the envelope contract. It is never retried successfully, so it ends in the DLQ.
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }
}
