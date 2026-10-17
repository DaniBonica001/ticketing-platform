package com.dbonilla.ticketing.expirer.usecase.shared;

/**
 * How overdue orders are read and processed.
 *
 * @param pageSize    {@code Limit} of every index query
 * @param concurrency orders of one status processed in parallel
 */
public record ScanSettings(int pageSize, int concurrency) {

    public ScanSettings {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
    }
}
