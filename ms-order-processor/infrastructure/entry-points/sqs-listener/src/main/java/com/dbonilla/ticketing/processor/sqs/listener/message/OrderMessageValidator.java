package com.dbonilla.ticketing.processor.sqs.listener.message;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a message body against contracts/messages/order-message.schema.json
 * (required fields, no additional properties, formats, {@code version == 1}) and the type expected by the queue.
 */
public class OrderMessageValidator {

    public static final int SUPPORTED_VERSION = 1;

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("messageId", "type", "version", "occurredAt", "producer", "payload");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("orderId", "eventId");
    private static final Set<String> PRODUCERS = Set.of("ticketing-api", "reservation-expirer");

    private final JsonMapper jsonMapper;

    public OrderMessageValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public OrderMessage validate(String body, MessageType expectedType) {
        JsonNode envelope = readObject(body);
        requireOnlyFields(envelope, ENVELOPE_FIELDS, "envelope");
        MessageType type = requireType(envelope, expectedType);
        int version = requireVersion(envelope);
        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new InvalidMessageException("Field 'payload' is required and must be an object");
        }
        requireOnlyFields(payload, PAYLOAD_FIELDS, "payload");
        return new OrderMessage(
                requireUuid(envelope, "messageId"),
                type,
                version,
                requireTimestamp(envelope),
                optionalProducer(envelope),
                requireUuid(payload, "orderId"),
                requireUuid(payload, "eventId"));
    }

    private JsonNode readObject(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidMessageException("The message body is empty");
        }
        try {
            JsonNode node = jsonMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new InvalidMessageException("The message body is not a JSON object");
            }
            return node;
        } catch (JacksonException malformed) {
            throw new InvalidMessageException("The message body is not valid JSON");
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed, String location) {
        node.propertyNames().stream()
                .filter(name -> !allowed.contains(name))
                .findFirst()
                .ifPresent(name -> {
                    throw new InvalidMessageException("Unknown field '" + name + "' in " + location);
                });
    }

    private static MessageType requireType(JsonNode envelope, MessageType expectedType) {
        String wireName = requireString(envelope, "type");
        MessageType type = MessageType.fromWireName(wireName)
                .orElseThrow(() -> new InvalidMessageException("Unknown message type"));
        if (type != expectedType) {
            throw new InvalidMessageException("Message type " + type.wireName()
                    + " does not belong to the queue of " + expectedType.wireName());
        }
        return type;
    }

    private static int requireVersion(JsonNode envelope) {
        JsonNode version = envelope.get("version");
        if (version == null || !version.isIntegralNumber() || version.asInt() != SUPPORTED_VERSION) {
            throw new InvalidMessageException("Field 'version' must be " + SUPPORTED_VERSION);
        }
        return SUPPORTED_VERSION;
    }

    private static OffsetDateTime requireTimestamp(JsonNode envelope) {
        String value = requireString(envelope, "occurredAt");
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new InvalidMessageException("Field 'occurredAt' is not an ISO-8601 date-time");
        }
    }

    private static String optionalProducer(JsonNode envelope) {
        if (!envelope.has("producer")) {
            return null;
        }
        String producer = requireString(envelope, "producer");
        if (!PRODUCERS.contains(producer)) {
            throw new InvalidMessageException("Unknown producer");
        }
        return producer;
    }

    private static String requireUuid(JsonNode node, String field) {
        String value = requireString(node, field);
        if (!UUID.matcher(value).matches()) {
            throw new InvalidMessageException("Field '" + field + "' is not a UUID");
        }
        return value;
    }

    private static String requireString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new InvalidMessageException("Field '" + field + "' is required and must be a string");
        }
        return value.asString();
    }
}
