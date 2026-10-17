package com.dbonilla.ticketing.api.model.event;

import java.time.Instant;

/**
 * Snapshot of the inventory of an event at a given instant.
 */
public record Availability(String eventId, Inventory inventory, Instant asOf) {
}
