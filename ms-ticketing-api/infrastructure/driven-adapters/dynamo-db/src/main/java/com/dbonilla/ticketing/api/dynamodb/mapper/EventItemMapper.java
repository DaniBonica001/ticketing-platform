package com.dbonilla.ticketing.api.dynamodb.mapper;

import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.Inventory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.instant;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.n;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.number;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.optionalInstant;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.s;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.string;
import static com.dbonilla.ticketing.api.dynamodb.support.Attributes.timestamp;

/**
 * Item layout of {@code ticketing-events} (dynamodb.md, section 1).
 */
public final class EventItemMapper {

    public static final String EVENT_ID = "eventId";
    public static final String NAME = "name";
    public static final String EVENT_DATE = "eventDate";
    public static final String VENUE = "venue";
    public static final String CAPACITY = "capacity";
    public static final String AVAILABLE = "available";
    public static final String RESERVED = "reserved";
    public static final String PENDING_CONFIRMATION = "pendingConfirmation";
    public static final String SOLD = "sold";
    public static final String COMPLIMENTARY = "complimentary";
    public static final String VERSION = "version";
    public static final String CREATED_AT = "createdAt";
    public static final String UPDATED_AT = "updatedAt";

    private EventItemMapper() {
    }

    public static Map<String, AttributeValue> key(String eventId) {
        return Map.of(EVENT_ID, s(eventId));
    }

    public static String formatEventDate(OffsetDateTime eventDate) {
        return eventDate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static Map<String, AttributeValue> toItem(Event event) {
        Inventory inventory = event.inventory();
        return Map.ofEntries(
                Map.entry(EVENT_ID, s(event.eventId())),
                Map.entry(NAME, s(event.details().name())),
                Map.entry(EVENT_DATE, s(formatEventDate(event.details().eventDate()))),
                Map.entry(VENUE, s(event.details().venue())),
                Map.entry(CAPACITY, n(inventory.capacity())),
                Map.entry(AVAILABLE, n(inventory.available())),
                Map.entry(RESERVED, n(inventory.reserved())),
                Map.entry(PENDING_CONFIRMATION, n(inventory.pendingConfirmation())),
                Map.entry(SOLD, n(inventory.sold())),
                Map.entry(COMPLIMENTARY, n(inventory.complimentary())),
                Map.entry(VERSION, n(event.version())),
                Map.entry(CREATED_AT, timestamp(event.createdAt())),
                Map.entry(UPDATED_AT, timestamp(event.updatedAt())));
    }

    public static Event toEvent(Map<String, AttributeValue> item) {
        EventDetails details = new EventDetails(string(item, NAME),
                OffsetDateTime.parse(string(item, EVENT_DATE)), string(item, VENUE));
        Inventory inventory = new Inventory(counter(item, CAPACITY), counter(item, AVAILABLE),
                counter(item, RESERVED), counter(item, PENDING_CONFIRMATION), counter(item, SOLD),
                counter(item, COMPLIMENTARY));
        return new Event(string(item, EVENT_ID), details, inventory, number(item, VERSION),
                instant(item, CREATED_AT), optionalInstant(item, UPDATED_AT).orElse(null));
    }

    private static int counter(Map<String, AttributeValue> item, String name) {
        return Math.toIntExact(number(item, name));
    }
}
