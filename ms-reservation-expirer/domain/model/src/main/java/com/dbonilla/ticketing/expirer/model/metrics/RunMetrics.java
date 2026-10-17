package com.dbonilla.ticketing.expirer.model.metrics;

/**
 * Counts produced by one expirer run, published as business metrics.
 */
public record RunMetrics(int expired, int republishedQueued, int republishedPendingConfirmation, int failures) {

    public RunMetrics {
        if (expired < 0 || republishedQueued < 0 || republishedPendingConfirmation < 0 || failures < 0) {
            throw new IllegalArgumentException("run metrics cannot be negative");
        }
    }
}
