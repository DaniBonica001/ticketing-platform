package com.dbonilla.ticketing.api.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PlaceOrderRequest(
        @NotNull @Pattern(regexp = UUID_PATTERN, message = "must be a UUID") String eventId,
        @NotNull @Min(1) @Max(10) Integer quantity) {

    public static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
}
