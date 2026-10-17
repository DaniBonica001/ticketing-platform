package com.dbonilla.ticketing.processor.dynamodb.config;

import java.util.Objects;

public record TableNames(String events, String orders, String audit) {

    public TableNames {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(audit, "audit");
    }
}
