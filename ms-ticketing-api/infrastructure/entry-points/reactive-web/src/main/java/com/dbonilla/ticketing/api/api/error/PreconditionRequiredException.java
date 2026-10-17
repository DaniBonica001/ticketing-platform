package com.dbonilla.ticketing.api.api.error;

/**
 * Conditional request without {@code If-Match} (428 PRECONDITION_REQUIRED).
 */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("The If-Match header is required");
    }
}
