package com.dbonilla.ticketing.api.dynamodb.support;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * Conversions between Java values and DynamoDB attribute values.
 */
public final class Attributes {

    /** ISO-8601 UTC with milliseconds, e.g. {@code 2026-10-16T10:00:01.123Z}. */
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Attributes() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    public static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    public static AttributeValue timestamp(Instant value) {
        return s(TIMESTAMP.format(value));
    }

    public static String string(Map<String, AttributeValue> item, String name) {
        return optionalString(item, name)
                .orElseThrow(() -> new IllegalStateException("Missing attribute " + name));
    }

    public static Optional<String> optionalString(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::s);
    }

    public static long number(Map<String, AttributeValue> item, String name) {
        return optionalNumber(item, name)
                .orElseThrow(() -> new IllegalStateException("Missing attribute " + name));
    }

    public static Optional<Long> optionalNumber(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::n).map(Long::parseLong);
    }

    public static Instant instant(Map<String, AttributeValue> item, String name) {
        return Instant.parse(string(item, name));
    }

    public static Optional<Instant> optionalInstant(Map<String, AttributeValue> item, String name) {
        return optionalString(item, name).map(Instant::parse);
    }

    public static boolean isPresent(Map<String, AttributeValue> item) {
        return item != null && !item.isEmpty();
    }
}
