package com.dbonilla.ticketing.processor.sqs.listener.message;

import com.dbonilla.ticketing.processor.sqs.listener.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderMessageValidatorTest {

    private final OrderMessageValidator validator = new OrderMessageValidator(JsonMapper.builder().build());

    @Test
    void acceptsAValidEnvelope() {
        OrderMessage message = validator.validate(TestMessages.body("OrderPlaced"), MessageType.ORDER_PLACED);

        assertThat(message.messageId()).isEqualTo(TestMessages.MESSAGE_ID);
        assertThat(message.type()).isEqualTo(MessageType.ORDER_PLACED);
        assertThat(message.version()).isEqualTo(1);
        assertThat(message.producer()).isEqualTo("ticketing-api");
        assertThat(message.orderId()).isEqualTo(TestMessages.ORDER_ID);
        assertThat(message.eventId()).isEqualTo(TestMessages.EVENT_ID);
        assertThat(message.occurredAt()).isNotNull();
    }

    @Test
    void producerIsOptional() {
        String body = """
                {"messageId":"%s","type":"OrderConfirmationRequested","version":1,"occurredAt":"2026-10-16T10:00:00Z",
                 "payload":{"orderId":"%s","eventId":"%s"}}
                """.formatted(TestMessages.MESSAGE_ID, TestMessages.ORDER_ID, TestMessages.EVENT_ID);

        OrderMessage message = validator.validate(body, MessageType.ORDER_CONFIRMATION_REQUESTED);

        assertThat(message.producer()).isNull();
    }

    @Test
    void rejectsTypeOfTheOtherQueue() {
        assertThatThrownBy(() -> validator.validate(TestMessages.body("OrderConfirmationRequested"), MessageType.ORDER_PLACED))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("does not belong");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "[1,2]",
            "{\"type\":\"OrderPlaced\"}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"Unknown\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":2,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":\"1\",\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"not-a-uuid\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"yesterday\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":\"x\"}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\",\"quantity\":5}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"extra\":true,\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}",
            "{\"messageId\":\"7d1c0b8e-3f0a-4a8e-9a55-2b8f1c4e9d10\",\"type\":\"OrderPlaced\",\"version\":1,\"occurredAt\":\"2026-10-16T10:00:00Z\",\"producer\":\"hacker\",\"payload\":{\"orderId\":\"3f2c9a54-1b7e-5d2a-8c4f-0e6b9d1a7c33\",\"eventId\":\"8b0e4f1a-6c2d-4e9b-b1a3-5d7c9e0f2a64\"}}"
    })
    void rejectsMessagesThatBreakTheContract(String body) {
        assertThatThrownBy(() -> validator.validate(body, MessageType.ORDER_PLACED))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void rejectsNullBody() {
        assertThatThrownBy(() -> validator.validate(null, MessageType.ORDER_PLACED))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void resolvesWireNames() {
        assertThat(MessageType.fromWireName("OrderPlaced")).contains(MessageType.ORDER_PLACED);
        assertThat(MessageType.fromWireName("Nope")).isEmpty();
    }
}
