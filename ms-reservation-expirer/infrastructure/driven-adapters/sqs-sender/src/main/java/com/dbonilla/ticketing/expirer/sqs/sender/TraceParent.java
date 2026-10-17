package com.dbonilla.ticketing.expirer.sqs.sender;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * W3C {@code traceparent} ({@code 00-<trace-id>-<parent-id>-01}) carried as an SQS message attribute (ADR-0007).
 */
final class TraceParent {

    private static final Pattern TRACE_ID = Pattern.compile("^(?!0{32})[0-9a-f]{32}$");
    private static final String VERSION = "00";
    private static final String SAMPLED = "01";

    private TraceParent() {
    }

    static String of(String traceId) {
        if (!TRACE_ID.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId must be 32 lowercase hex characters, not all zero");
        }
        return VERSION + "-" + traceId + "-" + newSpanId() + "-" + SAMPLED;
    }

    static String newSpanId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0L);
        return HexFormat.of().toHexDigits(id);
    }
}
