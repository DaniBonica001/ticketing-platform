package com.dbonilla.ticketing.api.api.error;

import java.net.URI;
import java.util.Locale;

/**
 * HTTP error catalog (error-handling-and-retries.md, section 4).
 */
public enum ProblemType {
    VALIDATION_ERROR(400, "Validation error"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    EVENT_NOT_FOUND(404, "Event not found"),
    ORDER_NOT_FOUND(404, "Order not found"),
    INVALID_ORDER_STATE(409, "Invalid order state"),
    RESERVATION_EXPIRED(409, "Reservation expired"),
    INSUFFICIENT_INVENTORY(409, "Insufficient inventory"),
    VERSION_CONFLICT(412, "Version conflict"),
    PAYLOAD_TOO_LARGE(413, "Payload too large"),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency key reused"),
    PRECONDITION_REQUIRED(428, "Precondition required"),
    RATE_LIMITED(429, "Too many requests"),
    INTERNAL_ERROR(500, "Internal error"),
    SERVICE_UNAVAILABLE(503, "Service unavailable");

    private static final String TYPE_BASE = "https://ticketing.local/problems/";

    private final int status;
    private final String title;

    ProblemType(int status, String title) {
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        return URI.create(TYPE_BASE + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
