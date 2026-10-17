package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderPolicy;
import com.dbonilla.ticketing.api.model.order.OrderStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final OrderPolicy POLICY = new OrderPolicy(Duration.ofMinutes(5));
    public static final String EVENT_ID = "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64";
    public static final String ORDER_ID = "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33";
    public static final String USER_ID = "user-1";
    public static final EventDetails DETAILS = new EventDetails("Concert",
            OffsetDateTime.of(2026, 12, 20, 20, 0, 0, 0, ZoneOffset.ofHours(-5)), "Stadium");

    private Fixtures() {
    }

    public static Event event(Inventory inventory) {
        return new Event(EVENT_ID, DETAILS, inventory, 1, NOW, NOW);
    }

    public static Event event() {
        return event(Inventory.initial(5));
    }

    public static Order order(OrderStatus status, Instant deadlineAt, String paymentReference) {
        return new Order(ORDER_ID, EVENT_ID, USER_ID, 2, status, null, deadlineAt, "hash", paymentReference,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }
}
