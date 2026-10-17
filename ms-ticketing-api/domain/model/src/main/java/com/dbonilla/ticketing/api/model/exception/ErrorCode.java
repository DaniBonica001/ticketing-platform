package com.dbonilla.ticketing.api.model.exception;

/**
 * Business error codes raised by the domain. They mirror the {@code code} values of the API contract.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    EVENT_NOT_FOUND,
    ORDER_NOT_FOUND,
    INVALID_ORDER_STATE,
    RESERVATION_EXPIRED,
    INSUFFICIENT_INVENTORY,
    VERSION_CONFLICT,
    IDEMPOTENCY_KEY_REUSED,
    SERVICE_UNAVAILABLE
}
