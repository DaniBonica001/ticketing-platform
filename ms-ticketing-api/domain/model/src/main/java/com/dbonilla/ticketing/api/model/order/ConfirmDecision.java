package com.dbonilla.ticketing.api.model.order;

/**
 * Outcome of evaluating a confirmation against the current order state.
 */
public sealed interface ConfirmDecision {

    /** The order must move to PENDING_CONFIRMATION. */
    record Apply(Order next) implements ConfirmDecision {
    }

    /** The same confirmation was already applied: idempotent replay. */
    record AlreadyConfirmed(Order current) implements ConfirmDecision {
    }
}
