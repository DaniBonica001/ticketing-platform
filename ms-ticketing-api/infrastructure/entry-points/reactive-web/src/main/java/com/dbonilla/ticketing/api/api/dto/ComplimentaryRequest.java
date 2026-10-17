package com.dbonilla.ticketing.api.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ComplimentaryRequest(
        @NotNull @Min(1) @Max(10) Integer quantity,
        @NotBlank @Size(max = 128) String recipientUserId) {
}
