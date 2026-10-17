package com.dbonilla.ticketing.processor.sqs.listener.message;

import java.time.OffsetDateTime;

/**
 * A validated envelope of the order queues (thin message: identifiers only).
 */
public record OrderMessage(String messageId, MessageType type, int version, OffsetDateTime occurredAt,
                           String producer, String orderId, String eventId) {
}
