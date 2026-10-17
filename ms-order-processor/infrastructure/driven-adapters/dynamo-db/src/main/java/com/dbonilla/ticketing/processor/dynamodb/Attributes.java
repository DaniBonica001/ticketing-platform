package com.dbonilla.ticketing.processor.dynamodb;

/**
 * Attribute names of the tables (docs/design/dynamodb.md).
 */
public final class Attributes {

    public static final String ORDER_ID = "orderId";
    public static final String EVENT_ID = "eventId";
    public static final String USER_ID = "userId";
    public static final String QUANTITY = "quantity";
    public static final String STATUS = "status";
    public static final String REASON = "reason";
    public static final String DEADLINE_AT = "deadlineAt";
    public static final String PAYMENT_REFERENCE = "paymentReference";
    public static final String UPDATED_AT = "updatedAt";
    public static final String TRANSITION_ID = "transitionId";
    public static final String FROM_STATUS = "fromStatus";
    public static final String TO_STATUS = "toStatus";
    public static final String ACTOR = "actor";
    public static final String TRACE_ID = "traceId";
    public static final String OCCURRED_AT = "occurredAt";

    public static final String AVAILABLE = "available";
    public static final String RESERVED = "reserved";
    public static final String PENDING_CONFIRMATION = "pendingConfirmation";
    public static final String SOLD = "sold";
    public static final String COMPLIMENTARY = "complimentary";

    private Attributes() {
    }
}
