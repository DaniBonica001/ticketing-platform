package com.dbonilla.ticketing.api.api.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ISO-8601 UTC rendering with milliseconds, e.g. {@code 2026-10-16T10:00:01.123Z}.
 */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
