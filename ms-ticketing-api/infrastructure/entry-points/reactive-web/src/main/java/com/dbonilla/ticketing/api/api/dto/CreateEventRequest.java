package com.dbonilla.ticketing.api.api.dto;

import com.dbonilla.ticketing.api.model.event.EventDetails;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateEventRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull @Future OffsetDateTime eventDate,
        @NotBlank @Size(max = 120) String venue,
        @NotNull @Min(1) @Max(100_000) Integer capacity) {

    public EventDetails details() {
        return new EventDetails(name, eventDate, venue);
    }
}
