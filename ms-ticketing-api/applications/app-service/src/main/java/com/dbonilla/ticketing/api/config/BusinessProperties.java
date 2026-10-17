package com.dbonilla.ticketing.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Business parameters of conventions.md, section 6 ({@code ticketing.*}).
 */
@ConfigurationProperties(prefix = "ticketing")
public record BusinessProperties(Reservation reservation, Reconciliation reconciliation) {

    public BusinessProperties {
        Objects.requireNonNull(reservation, "ticketing.reservation");
        Objects.requireNonNull(reconciliation, "ticketing.reconciliation");
    }

    /** @param ttl reservation length; enforced by the processor (T2), informative here */
    public record Reservation(Duration ttl) {
    }

    /** @param deadline deadline of QUEUED and PENDING_CONFIRMATION orders before reconciliation */
    public record Reconciliation(Duration deadline) {
    }
}
