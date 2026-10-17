package com.dbonilla.ticketing.processor.model.processing;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded exponential backoff with jitter used for transient failures.
 */
public record RetryPolicy(long maxRetries, Duration minBackoff, Duration maxBackoff, double jitter) {

    public RetryPolicy {
        Objects.requireNonNull(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be between 0 and 1");
        }
    }
}
