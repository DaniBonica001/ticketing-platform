package com.dbonilla.ticketing.processor.paymentgateway;

import java.util.Objects;

/**
 * Credential of the payment gateway. {@link #toString()} never reveals the value.
 */
public record ApiKey(String value) {

    public ApiKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("The API key must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ApiKey[****]";
    }
}
