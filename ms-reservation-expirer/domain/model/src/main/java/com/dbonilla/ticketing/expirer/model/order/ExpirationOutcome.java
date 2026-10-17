package com.dbonilla.ticketing.expirer.model.order;

/**
 * Result of applying T7 (RESERVED to EXPIRED) to one order.
 */
public enum ExpirationOutcome {
    /** The order expired and its tickets went back to available. */
    EXPIRED,
    /** The order was no longer RESERVED and overdue (e.g. the user confirmed concurrently): nothing written. */
    ALREADY_TRANSITIONED
}
