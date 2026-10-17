package com.dbonilla.ticketing.api.dynamodb;

import com.dbonilla.ticketing.api.dynamodb.mapper.EventItemMapper;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Opaque pagination cursor: Base64URL of the {@code LastEvaluatedKey} (the event id).
 */
final class EventCursor {

    private EventCursor() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || !lastEvaluatedKey.containsKey(EventItemMapper.EVENT_ID)) {
            return null;
        }
        byte[] key = lastEvaluatedKey.get(EventItemMapper.EVENT_ID).s().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    static Map<String, AttributeValue> decode(String cursor) {
        try {
            String eventId = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            return EventItemMapper.key(UUID.fromString(eventId).toString());
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("cursor", "cursor is invalid");
        }
    }
}
