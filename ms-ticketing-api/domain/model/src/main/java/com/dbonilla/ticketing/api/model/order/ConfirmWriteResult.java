package com.dbonilla.ticketing.api.model.order;

/**
 * Result of the conditional confirmation transaction (T4).
 */
public sealed interface ConfirmWriteResult {

    record Confirmed(Order order) implements ConfirmWriteResult {
    }

    /**
     * The order condition failed. {@code current} is the stored order, or {@code null} when it no longer
     * exists.
     */
    record Conflict(Order current) implements ConfirmWriteResult {
    }
}
