package com.dbonilla.ticketing.processor.sqs.listener.message;

import java.util.Arrays;
import java.util.Optional;

/**
 * Message types of contracts/messages/order-message.schema.json.
 */
public enum MessageType {
    ORDER_PLACED("OrderPlaced"),
    ORDER_CONFIRMATION_REQUESTED("OrderConfirmationRequested");

    private final String wireName;

    MessageType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<MessageType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }
}
