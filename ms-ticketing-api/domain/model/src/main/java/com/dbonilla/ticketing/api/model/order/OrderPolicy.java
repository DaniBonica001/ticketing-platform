package com.dbonilla.ticketing.api.model.order;

import java.time.Duration;
import java.util.Objects;

/**
 * Business timing parameters applied by the API.
 *
 * @param reconciliationDeadline deadline of QUEUED and PENDING_CONFIRMATION orders before the expirer
 *                               re-publishes them (ADR-0005)
 */
public record OrderPolicy(Duration reconciliationDeadline) {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 10;

    public OrderPolicy {
        Objects.requireNonNull(reconciliationDeadline, "reconciliationDeadline");
        if (reconciliationDeadline.isNegative() || reconciliationDeadline.isZero()) {
            throw new IllegalArgumentException("reconciliationDeadline must be positive");
        }
    }
}
