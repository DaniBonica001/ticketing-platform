package com.dbonilla.ticketing.api.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code ticketing.rate-limit.*}: per user on POST /orders and per client IP on public endpoints.
 */
@ConfigurationProperties(prefix = "ticketing.rate-limit")
public record RateLimitProperties(Limit orders, Limit publicEndpoints, Long maxKeys, Duration idleTimeout) {

    public static final Limit DEFAULT_ORDERS = new Limit(5, 10);
    public static final Limit DEFAULT_PUBLIC = new Limit(50, 50);
    public static final long DEFAULT_MAX_KEYS = 100_000;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(1);

    public RateLimitProperties {
        orders = orders == null ? DEFAULT_ORDERS : orders;
        publicEndpoints = publicEndpoints == null ? DEFAULT_PUBLIC : publicEndpoints;
        maxKeys = maxKeys == null ? DEFAULT_MAX_KEYS : maxKeys;
        idleTimeout = idleTimeout == null ? DEFAULT_IDLE_TIMEOUT : idleTimeout;
    }

    public record Limit(double requestsPerSecond, int burst) {

        public Limit {
            if (requestsPerSecond <= 0 || burst < 1) {
                throw new IllegalArgumentException("Rate limits must be positive");
            }
        }
    }
}
