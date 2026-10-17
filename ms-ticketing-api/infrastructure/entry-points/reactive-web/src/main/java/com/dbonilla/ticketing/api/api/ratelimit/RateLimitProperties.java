package com.dbonilla.ticketing.api.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * {@code ticketing.rate-limit.*}: per user on the authenticated mutations (POST /orders, confirmations and admin
 * operations), per client IP on public endpoints, and a cap on concurrent availability streams.
 *
 * @param trustedProxies      CIDRs of the proxies allowed to set {@code clientIpHeader} (in AWS, the subnets of the
 *                            internal NLB). Empty (default): the TCP peer address is always the client
 * @param clientIpHeader      header carrying the client IP, read only from a trusted proxy (default {@code X-Client-Ip})
 * @param confirm             per user on {@code POST /orders/{id}/confirm}
 * @param adminOperations     per user on event creation/edition and complimentary tickets
 * @param availabilityStreams concurrent SSE availability streams, per client and in the whole instance
 */
@ConfigurationProperties(prefix = "ticketing.rate-limit")
public record RateLimitProperties(Limit orders, Limit publicEndpoints, Long maxKeys, Duration idleTimeout,
                                  List<String> trustedProxies, String clientIpHeader, Limit confirm,
                                  Limit adminOperations, StreamLimit availabilityStreams) {

    public static final Limit DEFAULT_ORDERS = new Limit(5, 10);
    public static final Limit DEFAULT_PUBLIC = new Limit(50, 50);
    public static final Limit DEFAULT_CONFIRM = new Limit(2, 5);
    public static final Limit DEFAULT_ADMIN_OPERATIONS = new Limit(5, 10);
    public static final StreamLimit DEFAULT_AVAILABILITY_STREAMS = new StreamLimit(3, 1000);
    public static final long DEFAULT_MAX_KEYS = 100_000;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(1);
    public static final String DEFAULT_CLIENT_IP_HEADER = "X-Client-Ip";

    public RateLimitProperties {
        orders = orders == null ? DEFAULT_ORDERS : orders;
        publicEndpoints = publicEndpoints == null ? DEFAULT_PUBLIC : publicEndpoints;
        maxKeys = maxKeys == null ? DEFAULT_MAX_KEYS : maxKeys;
        idleTimeout = idleTimeout == null ? DEFAULT_IDLE_TIMEOUT : idleTimeout;
        trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().filter(cidr -> cidr != null && !cidr.isBlank()).map(String::trim).toList();
        clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? DEFAULT_CLIENT_IP_HEADER : clientIpHeader;
        confirm = confirm == null ? DEFAULT_CONFIRM : confirm;
        adminOperations = adminOperations == null ? DEFAULT_ADMIN_OPERATIONS : adminOperations;
        availabilityStreams = availabilityStreams == null ? DEFAULT_AVAILABILITY_STREAMS : availabilityStreams;
    }

    public record Limit(double requestsPerSecond, int burst) {

        public Limit {
            if (requestsPerSecond <= 0 || burst < 1) {
                throw new IllegalArgumentException("Rate limits must be positive");
            }
        }
    }

    /** Maximum number of streams open at the same time by one client and by all clients. */
    public record StreamLimit(int perClient, int global) {

        public StreamLimit {
            if (perClient < 1 || global < 1) {
                throw new IllegalArgumentException("Stream limits must be positive");
            }
        }
    }
}
