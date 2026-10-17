package com.dbonilla.ticketing.expirer.model.order;

/**
 * Result of moving the reconciliation deadline of a stuck order.
 */
public enum DeadlineUpdate {
    /** The deadline was moved forward. */
    POSTPONED,
    /** The order left the expected status meanwhile (it was processed): nothing written. */
    STATE_CHANGED
}
