package com.dbonilla.ticketing.expirer.lambda;

import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpirationResult;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconciliationResult;

/**
 * Result returned by every invocation (also written as the summary log line).
 */
public record ExpirationSummary(int expired, int skipped, int republishedQueued,
                                int republishedPendingConfirmation, int failures) {

    static ExpirationSummary of(ExpirationResult expiration, ReconciliationResult reconciliation) {
        return new ExpirationSummary(expiration.expired(), expiration.skipped(),
                reconciliation.republishedQueued(), reconciliation.republishedPendingConfirmation(),
                expiration.failures().size() + reconciliation.failures().size());
    }
}
