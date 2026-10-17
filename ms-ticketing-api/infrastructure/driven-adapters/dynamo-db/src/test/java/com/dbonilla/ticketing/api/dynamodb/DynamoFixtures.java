package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.config.DynamoTablesProperties;
import com.dbonilla.ticketing.api.dynamodb.support.DynamoExecutor;
import com.dbonilla.ticketing.api.dynamodb.support.TransactionMetrics;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.order.Order;
import com.dbonilla.ticketing.api.model.order.OrderStatus;
import com.dbonilla.ticketing.api.resilience.RetryProperties;
import com.dbonilla.ticketing.api.resilience.TransientRetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

public final class DynamoFixtures {

    public static final Instant NOW = Instant.parse("2026-10-16T10:00:00.123Z");
    public static final String EVENT_ID = "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64";
    public static final String ORDER_ID = "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33";
    public static final DynamoTablesProperties TABLES =
            new DynamoTablesProperties("ticketing-events", "ticketing-orders", "ticketing-order-audit");

    private DynamoFixtures() {
    }

    public static DynamoExecutor executor(SimpleMeterRegistry registry) {
        RetryProperties fast = new RetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(2), 0.5);
        return new DynamoExecutor(new TransientRetry(fast), new TransactionMetrics(registry));
    }

    public static Event event() {
        return new Event(EVENT_ID, new EventDetails("Concert", OffsetDateTime.parse("2026-12-20T20:00:00-05:00"),
                "Stadium"), new Inventory(5, 3, 1, 0, 1, 0), 2, NOW, NOW);
    }

    public static Order order(OrderStatus status) {
        return new Order(ORDER_ID, EVENT_ID, "user-1", 2, status, null, Instant.ofEpochSecond(NOW.getEpochSecond() + 300),
                "hash", null, NOW, NOW);
    }

    public static TransactionCanceledException canceled(CancellationReason... reasons) {
        return TransactionCanceledException.builder().message("Transaction cancelled")
                .cancellationReasons(reasons).build();
    }

    public static CancellationReason reason(String code) {
        return CancellationReason.builder().code(code).build();
    }

    public static CancellationReason reason(String code, Map<String, AttributeValue> item) {
        return CancellationReason.builder().code(code).item(item).build();
    }
}
