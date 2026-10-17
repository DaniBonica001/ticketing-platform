package com.dbonilla.ticketing.api.model.event;

import com.dbonilla.ticketing.api.model.exception.DomainValidationException;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Editable data of an event (everything but the capacity, which cannot change).
 */
public record EventDetails(String name, OffsetDateTime eventDate, String venue) {

    public static final int MAX_TEXT_LENGTH = 120;

    public EventDetails {
        requireText("name", name);
        requireText("venue", venue);
        if (eventDate == null) {
            throw new DomainValidationException("eventDate", "eventDate is required");
        }
    }

    public EventDetails requireFutureDate(Instant now) {
        if (!eventDate.toInstant().isAfter(now)) {
            throw new DomainValidationException("eventDate", "eventDate must be in the future");
        }
        return this;
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new DomainValidationException(field,
                    field + " must have between 1 and " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
