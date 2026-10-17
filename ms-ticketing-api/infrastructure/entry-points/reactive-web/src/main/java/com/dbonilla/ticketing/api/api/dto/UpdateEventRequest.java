package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.event.EventDetails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record UpdateEventRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull OffsetDateTime eventDate,
        @NotBlank @Size(max = 120) String venue) {

    public EventDetails details() {
        return new EventDetails(name, eventDate, venue);
    }
}
