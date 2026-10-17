package com.dbonilla.ticketing.expirer.usecase.expirereservations;

import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;

import java.util.List;
import java.util.stream.Stream;

/**
 * Counts of one expiration run.
 */
public record ExpirationResult(int expired, int skipped, List<OrderFailure> failures) {

    public static final ExpirationResult EMPTY = new ExpirationResult(0, 0, List.of());

    public ExpirationResult {
        failures = List.copyOf(failures);
    }

    static ExpirationResult oneExpired() {
        return new ExpirationResult(1, 0, List.of());
    }

    static ExpirationResult oneSkipped() {
        return new ExpirationResult(0, 1, List.of());
    }

    static ExpirationResult failed(OrderFailure failure) {
        return new ExpirationResult(0, 0, List.of(failure));
    }

    public ExpirationResult plus(ExpirationResult other) {
        return new ExpirationResult(expired + other.expired, skipped + other.skipped,
                Stream.concat(failures.stream(), other.failures.stream()).toList());
    }
}
