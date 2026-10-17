package com.dbonilla.ticketing.api.model.event.gateways;

import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.EventPage;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface EventRepository {

    /** Stores a new event. */
    Mono<Event> create(Event event);

    /** Strongly consistent read; empty when the event does not exist. */
    Mono<Event> findById(String eventId);

    /** Paginated listing; {@code cursor} is the opaque value returned by the previous page. */
    Mono<EventPage> findPage(int limit, String cursor);

    /**
     * Optimistic-locking update of the event details. Fails with {@code EventNotFoundException} or
     * {@code VersionConflictException}.
     */
    Mono<Event> updateDetails(String eventId, EventDetails details, long expectedVersion, Instant now);
}
