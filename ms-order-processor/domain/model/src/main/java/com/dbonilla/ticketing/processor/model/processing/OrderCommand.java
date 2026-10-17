package com.dbonilla.ticketing.processor.model.processing;

import java.util.Objects;

/**
 * Request to process an order, built from a validated queue message. It only carries identifiers:
 * the order itself is always read from the source of truth.
 *
 * @param traceId trace id of the current processing, stored in the audit trail (may be {@code null})
 */
public record OrderCommand(String messageId, String orderId, String eventId, String traceId) {

    public OrderCommand {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
    }
}
