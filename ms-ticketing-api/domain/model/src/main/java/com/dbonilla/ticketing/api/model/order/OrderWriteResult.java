package com.dbonilla.ticketing.api.model.order;

import com.dbonilla.ticketing.api.model.exception.IdempotencyKeyReusedException;

/**
 * Result of a conditional order creation ({@code attribute_not_exists(orderId)}).
 */
public sealed interface OrderWriteResult {

    record Created(Order order) implements OrderWriteResult {
    }

    /** An order with the same id already existed; it is returned as stored. */
    record AlreadyExists(Order existing) implements OrderWriteResult {
    }

    /**
     * Idempotency rule (ADR-0004): a replay with the same payload returns the stored order; the same key
     * with another payload is rejected.
     */
    default Order resolve(String requestHash) {
        return switch (this) {
            case Created(Order order) -> order;
            case AlreadyExists(Order existing) when existing.matchesRequest(requestHash) -> existing;
            case AlreadyExists ignored -> throw new IdempotencyKeyReusedException();
        };
    }
}
