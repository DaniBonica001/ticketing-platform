package com.dbonilla.ticketing.api.dynamodb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;

@ConfigurationProperties(prefix = "adapter.dynamodb")
public record DynamoTablesProperties(String eventsTable, String ordersTable, String auditTable) {

    public DynamoTablesProperties {
        Objects.requireNonNull(eventsTable, "adapter.dynamodb.events-table");
        Objects.requireNonNull(ordersTable, "adapter.dynamodb.orders-table");
        Objects.requireNonNull(auditTable, "adapter.dynamodb.audit-table");
    }
}
