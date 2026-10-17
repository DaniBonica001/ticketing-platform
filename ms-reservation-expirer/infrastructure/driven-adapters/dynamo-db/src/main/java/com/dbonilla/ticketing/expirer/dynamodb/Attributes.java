package com.dbonilla.ticketing.expirer.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;

/**
 * Attribute names (docs/design/dynamodb.md) and value helpers.
 */
final class Attributes {

    static final String ORDER_ID = "orderId";
    static final String EVENT_ID = "eventId";
    static final String QUANTITY = "quantity";
    static final String STATUS = "status";
    static final String DEADLINE_AT = "deadlineAt";
    static final String UPDATED_AT = "updatedAt";
    static final String AVAILABLE = "available";
    static final String RESERVED = "reserved";
    static final String TRANSITION_ID = "transitionId";
    static final String FROM_STATUS = "fromStatus";
    static final String TO_STATUS = "toStatus";
    static final String ACTOR = "actor";
    static final String TRACE_ID = "traceId";
    static final String OCCURRED_AT = "occurredAt";

    /** Value of the audit {@code actor} attribute for this component. */
    static final String ACTOR_NAME = "reservation-expirer";

    private Attributes() {
    }

    static AttributeValue text(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    /** {@code deadlineAt} is stored as epoch seconds. */
    static AttributeValue epochSeconds(Instant instant) {
        return number(instant.getEpochSecond());
    }
}
