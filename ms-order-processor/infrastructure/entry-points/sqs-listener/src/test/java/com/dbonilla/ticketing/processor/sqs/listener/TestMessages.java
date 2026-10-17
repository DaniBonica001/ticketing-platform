package com.dbonilla.ticketing.processor.sqs.listener;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.util.Map;

public final class TestMessages {

    public static final String MESSAGE_ID = "7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10";
    public static final String ORDER_ID = "3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33";
    public static final String EVENT_ID = "8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64";
    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    public static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    private TestMessages() {
    }

    public static String body(String type) {
        return """
                {"messageId":"%s","type":"%s","version":1,"occurredAt":"2026-10-16T10:00:00.123Z",
                 "producer":"ticketing-api","payload":{"orderId":"%s","eventId":"%s"}}
                """.formatted(MESSAGE_ID, type, ORDER_ID, EVENT_ID);
    }

    public static Message message(String body) {
        return Message.builder()
                .messageId("sqs-1")
                .receiptHandle("receipt-1")
                .body(body)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1"))
                .messageAttributes(Map.of("traceparent",
                        MessageAttributeValue.builder().dataType("String").stringValue(TRACEPARENT).build()))
                .build();
    }
}
