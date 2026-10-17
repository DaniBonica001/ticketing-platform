package com.dbonilla.ticketing.expirer.model.execution;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ISO-8601 UTC timestamps with exactly three fraction digits ({@code 2026-10-16T10:00:01.123Z}).
 */
public final class UtcTimestamps {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private UtcTimestamps() {
    }

    public static String format(Instant instant) {
        return FORMAT.format(instant);
    }
}
