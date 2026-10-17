package com.dbonilla.ticketing.api.model.event;

import java.time.Instant;
import java.util.Objects;

/**
 * An event and its inventory. Immutable: every change returns a new instance.
 *
 * @param version optimistic-locking version, used only to edit the event details
 */
public record Event(String eventId, EventDetails details, Inventory inventory, long version,
                    Instant createdAt, Instant updatedAt) {

    public static final long INITIAL_VERSION = 1L;

    public Event {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Event create(String eventId, EventDetails details, int capacity, Instant now) {
        return new Event(eventId, details.requireFutureDate(now), Inventory.initial(capacity),
                INITIAL_VERSION, now, now);
    }

    public Availability availability(Instant asOf) {
        return new Availability(eventId, inventory, asOf);
    }
}
