package com.dbonilla.ticketing.api.model.exception;

public final class IdempotencyKeyReusedException extends TicketingException {

    public IdempotencyKeyReusedException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "The Idempotency-Key was already used with a different payload");
    }
}
