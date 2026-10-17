package com.dbonilla.ticketing.api.api.support;

import com.dbonilla.ticketing.api.api.error.PreconditionRequiredException;
import com.dbonilla.ticketing.api.api.error.RequestValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and validation of path variables, headers and query parameters.
 */
public final class RequestParams {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CURSOR_LENGTH = 512;
    private static final Pattern ETAG = Pattern.compile("^(?:W/)?\"?(\\d{1,18})\"?$");

    private RequestParams() {
    }

    /** Path variable that must be a UUID; returned in canonical (lower-case) form. */
    public static String uuidPath(ServerRequest request, String name) {
        return parseUuid(name, request.pathVariable(name));
    }

    public static String idempotencyKey(ServerRequest request) {
        String value = request.headers().firstHeader(IDEMPOTENCY_KEY);
        if (value == null || value.isBlank()) {
            throw RequestValidationException.of(IDEMPOTENCY_KEY, "the Idempotency-Key header is required");
        }
        return parseUuid(IDEMPOTENCY_KEY, value.trim());
    }

    /** Expected version from {@code If-Match}; 428 when the header is missing. */
    public static long expectedVersion(ServerRequest request) {
        String value = request.headers().firstHeader(HttpHeaders.IF_MATCH);
        if (value == null || value.isBlank()) {
            throw new PreconditionRequiredException();
        }
        Matcher matcher = ETAG.matcher(value.trim());
        if (!matcher.matches()) {
            throw RequestValidationException.of(HttpHeaders.IF_MATCH, "must be the ETag of the event, e.g. \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }

    public static int limit(ServerRequest request) {
        Optional<String> value = request.queryParam("limit");
        if (value.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value.get());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // reported below as a validation error
        }
        throw RequestValidationException.of("limit", "must be an integer between 1 and " + MAX_LIMIT);
    }

    public static String cursor(ServerRequest request) {
        String cursor = request.queryParam("cursor").filter(value -> !value.isBlank()).orElse(null);
        if (cursor != null && cursor.length() > MAX_CURSOR_LENGTH) {
            throw RequestValidationException.of("cursor", "must have at most " + MAX_CURSOR_LENGTH + " characters");
        }
        return cursor;
    }

    public static String canonicalUuid(String field, String value) {
        return parseUuid(field, value);
    }

    private static String parseUuid(String field, String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.toString().equalsIgnoreCase(value)) {
                return uuid.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // reported below as a validation error
        }
        throw RequestValidationException.of(field, "must be a UUID");
    }
}
