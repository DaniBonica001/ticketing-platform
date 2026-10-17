package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.event.Event;

import java.time.format.DateTimeFormatter;

public record EventResponse(String eventId, String name, String eventDate, String venue, int capacity,
                            AvailabilityResponse availability, long version, String createdAt,
                            String updatedAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.eventId(), event.details().name(),
                event.details().eventDate().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                event.details().venue(), event.inventory().capacity(),
                AvailabilityResponse.of(event.eventId(), event.inventory(), event.updatedAt()),
                event.version(), Timestamps.format(event.createdAt()), Timestamps.format(event.updatedAt()));
    }

    /** Quoted version, used as ETag and expected in If-Match. */
    public static String etag(Event event) {
        return "\"" + event.version() + "\"";
    }
}
