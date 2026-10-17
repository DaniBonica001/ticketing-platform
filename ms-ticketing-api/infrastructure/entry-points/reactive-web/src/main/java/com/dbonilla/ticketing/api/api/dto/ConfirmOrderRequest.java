package com.dbonilla.ticketing.api.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmOrderRequest(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{4,64}$",
                message = "must have 4 to 64 letters, digits or hyphens") String paymentReference) {
}
