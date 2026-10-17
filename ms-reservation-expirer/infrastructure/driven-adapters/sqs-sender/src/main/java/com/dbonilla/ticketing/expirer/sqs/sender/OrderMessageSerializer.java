package com.dbonilla.ticketing.expirer.sqs.sender;

import com.dbonilla.ticketing.expirer.model.execution.UtcTimestamps;
import com.dbonilla.ticketing.expirer.model.message.OrderMessage;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

/**
 * Writes the envelope of contracts/messages/order-message.schema.json with the streaming API
 * (jackson-core only, to keep the Lambda package small).
 */
final class OrderMessageSerializer {

    static final int SCHEMA_VERSION = 1;
    static final String PRODUCER = "reservation-expirer";

    private final JsonFactory factory = new JsonFactory();

    String toJson(OrderMessage message) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator json = factory.createGenerator(writer)) {
            json.writeStartObject();
            json.writeStringField("messageId", message.messageId().toString());
            json.writeStringField("type", message.type().contractName());
            json.writeNumberField("version", SCHEMA_VERSION);
            json.writeStringField("occurredAt", UtcTimestamps.format(message.occurredAt()));
            json.writeStringField("producer", PRODUCER);
            json.writeObjectFieldStart("payload");
            json.writeStringField("orderId", message.orderId());
            json.writeStringField("eventId", message.eventId());
            json.writeEndObject();
            json.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize order message", e);
        }
        return writer.toString();
    }
}
