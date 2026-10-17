package com.dbonilla.ticketing.api.sqs.sender;

/**
 * Envelope of contracts/messages/order-message.schema.json (version 1).
 */
record OrderMessage(String messageId, String type, int version, String occurredAt, String producer,
                    Payload payload) {

    static final int SCHEMA_VERSION = 1;
    static final String PRODUCER = "ticketing-api";
    static final String ORDER_PLACED = "OrderPlaced";
    static final String CONFIRMATION_REQUESTED = "OrderConfirmationRequested";

    record Payload(String orderId, String eventId) {
    }
}
