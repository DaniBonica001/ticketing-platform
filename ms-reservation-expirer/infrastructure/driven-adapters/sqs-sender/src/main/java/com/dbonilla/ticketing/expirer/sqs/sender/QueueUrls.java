package com.dbonilla.ticketing.expirer.sqs.sender;

import com.dbonilla.ticketing.expirer.model.message.OrderMessageType;

/**
 * ORDERS_QUEUE_URL and CONFIRMATIONS_QUEUE_URL.
 */
public record QueueUrls(String orders, String confirmations) {

    public QueueUrls {
        requireUrl(orders, "orders");
        requireUrl(confirmations, "confirmations");
    }

    /** OrderPlaced goes to orders-queue; OrderConfirmationRequested to confirmations-queue (ADR-0003). */
    public String urlFor(OrderMessageType type) {
        return switch (type) {
            case ORDER_PLACED -> orders;
            case ORDER_CONFIRMATION_REQUESTED -> confirmations;
        };
    }

    private static void requireUrl(String value, String queue) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(queue + " queue url is required");
        }
    }
}
