package com.dbonilla.ticketing.api.model.event;

import com.dbonilla.ticketing.api.model.exception.DomainValidationException;

/**
 * Ticket counters of an event. Every ticket is in exactly one state, so the counters always add up to
 * the capacity and none of them can be negative.
 */
public record Inventory(int capacity, int available, int reserved, int pendingConfirmation,
                        int sold, int complimentary) {

    public static final int MIN_CAPACITY = 1;
    public static final int MAX_CAPACITY = 100_000;

    public Inventory {
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw new DomainValidationException("capacity",
                    "capacity must be between " + MIN_CAPACITY + " and " + MAX_CAPACITY);
        }
        if (available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new IllegalStateException("Inventory counters cannot be negative");
        }
        if (available + reserved + pendingConfirmation + sold + complimentary != capacity) {
            throw new IllegalStateException("Inventory counters must add up to the capacity");
        }
    }

    public static Inventory initial(int capacity) {
        return new Inventory(capacity, capacity, 0, 0, 0, 0);
    }
}
