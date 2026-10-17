package com.dbonilla.ticketing.processor.model.order;

import java.util.Objects;

/**
 * Moves {@code quantity} tickets of an event from one inventory counter to another.
 * The persistence layer guarantees {@code counter(from) >= quantity} atomically.
 */
public record InventoryMovement(TicketState from, TicketState to) {

    public InventoryMovement {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from == to) {
            throw new IllegalArgumentException("An inventory movement needs two different counters");
        }
    }
}
