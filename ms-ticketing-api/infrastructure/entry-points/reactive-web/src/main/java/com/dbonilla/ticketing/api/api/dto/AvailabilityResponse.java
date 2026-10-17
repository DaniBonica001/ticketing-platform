package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.event.Availability;
import com.dbonilla.ticketing.api.model.event.Inventory;

import java.time.Instant;

public record AvailabilityResponse(String eventId, int capacity, int available, int reserved,
                                   int pendingConfirmation, int sold, int complimentary, String asOf) {

    public static AvailabilityResponse from(Availability availability) {
        return of(availability.eventId(), availability.inventory(), availability.asOf());
    }

    static AvailabilityResponse of(String eventId, Inventory inventory, Instant asOf) {
        return new AvailabilityResponse(eventId, inventory.capacity(), inventory.available(), inventory.reserved(),
                inventory.pendingConfirmation(), inventory.sold(), inventory.complimentary(),
                Timestamps.format(asOf));
    }
}
