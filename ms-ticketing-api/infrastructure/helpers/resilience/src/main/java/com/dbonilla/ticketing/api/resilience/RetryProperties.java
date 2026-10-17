package com.dbonilla.ticketing.api.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounded retry of transient DynamoDB/SQS errors ({@code ticketing.retry.*}).
 */
@ConfigurationProperties(prefix = "ticketing.retry")
public record RetryProperties(Integer maxAttempts, Duration minBackoff, Duration maxBackoff, Double jitter) {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final Duration DEFAULT_MIN_BACKOFF = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
    public static final double DEFAULT_JITTER = 0.5;

    public RetryProperties {
        maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        minBackoff = minBackoff == null ? DEFAULT_MIN_BACKOFF : minBackoff;
        maxBackoff = maxBackoff == null ? DEFAULT_MAX_BACKOFF : maxBackoff;
        jitter = jitter == null ? DEFAULT_JITTER : jitter;
    }

    public static RetryProperties defaults() {
        return new RetryProperties(null, null, null, null);
    }
}
