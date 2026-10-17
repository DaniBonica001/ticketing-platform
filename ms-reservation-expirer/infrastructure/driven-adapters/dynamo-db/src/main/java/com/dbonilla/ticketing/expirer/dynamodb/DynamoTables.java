package com.dbonilla.ticketing.expirer.dynamodb;

/**
 * Physical table names (EVENTS_TABLE, ORDERS_TABLE, AUDIT_TABLE).
 */
public record DynamoTables(String events, String orders, String audit) {

    /** Sparse GSI over orders: PK status (S), SK deadlineAt (N), projection ALL. */
    public static final String STATUS_DEADLINE_INDEX = "status-deadlineAt-index";

    public DynamoTables {
        requireName(events, "events");
        requireName(orders, "orders");
        requireName(audit, "audit");
    }

    private static void requireName(String value, String table) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(table + " table name is required");
        }
    }
}
