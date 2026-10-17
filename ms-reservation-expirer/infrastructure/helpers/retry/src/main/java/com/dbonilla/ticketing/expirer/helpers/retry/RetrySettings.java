package com.dbonilla.ticketing.expirer.helpers.retry;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded exponential backoff for transient errors.
 */
public record RetrySettings(int maxRetries, Duration minBackoff, Duration maxBackoff, double jitter) {

    /** Values fixed in docs/design/conventions.md, section 6. */
    public static final RetrySettings DEFAULT =
            new RetrySettings(3, Duration.ofMillis(100), Duration.ofSeconds(2), 0.5);

    public RetrySettings {
        Objects.requireNonNull(minBackoff, "minBackoff is required");
        Objects.requireNonNull(maxBackoff, "maxBackoff is required");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
    }
}
