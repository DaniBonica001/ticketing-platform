package com.dbonilla.ticketing.api.model;

import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;

import java.time.Duration;
import java.time.Instant;

public final class TestData {

    public static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    public static final String ORDER_ID = "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33";
    public static final String EVENT_ID = "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64";
    public static final String USER_ID = "user-1";

    private TestData() {
    }

    public static Order order(OrderStatus status, Instant deadlineAt, String paymentReference) {
        return new Order(ORDER_ID, EVENT_ID, USER_ID, 2, status, null, deadlineAt, "hash", paymentReference,
                NOW.minus(Duration.ofMinutes(1)), NOW.minus(Duration.ofMinutes(1)));
    }
}
