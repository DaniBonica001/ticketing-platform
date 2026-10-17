package com.dbonilla.ticketing.expirer.lambda;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Correlation id of an invocation: the X-Ray root trace id when Lambda provides one (it maps 1:1 to a W3C
 * trace id), otherwise a random one.
 */
final class TraceIds {

    static final String TRACE_HEADER_PROPERTY = "com.amazonaws.xray.traceHeader";
    static final String TRACE_HEADER_ENV = "_X_AMZN_TRACE_ID";
    private static final Pattern XRAY_ROOT = Pattern.compile("Root=1-([0-9a-fA-F]{8})-([0-9a-fA-F]{24})");
    private static final String ALL_ZERO = "0".repeat(32);

    private TraceIds() {
    }

    static String current() {
        String header = Optional.ofNullable(System.getProperty(TRACE_HEADER_PROPERTY))
                .orElseGet(() -> System.getenv(TRACE_HEADER_ENV));
        return fromXrayHeader(header).orElseGet(TraceIds::random);
    }

    static Optional<String> fromXrayHeader(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = XRAY_ROOT.matcher(header);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String traceId = (matcher.group(1) + matcher.group(2)).toLowerCase(Locale.ROOT);
        return ALL_ZERO.equals(traceId) ? Optional.empty() : Optional.of(traceId);
    }

    static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
