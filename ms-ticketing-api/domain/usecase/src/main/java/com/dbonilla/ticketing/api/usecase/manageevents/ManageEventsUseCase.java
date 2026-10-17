package com.dbonilla.ticketing.api.usecase.manageevents;

import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.EventPage;
import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Event catalog: create, read, list and edit (optimistic locking) events.
 */
public class ManageEventsUseCase {

    private final EventRepository eventRepository;
    private final Clock clock;

    public ManageEventsUseCase(EventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public Mono<Event> create(EventDetails details, int capacity) {
        return Mono.fromCallable(() -> Event.create(UUID.randomUUID().toString(), details, capacity, now()))
                .flatMap(eventRepository::create);
    }

    public Mono<Event> get(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)));
    }

    public Mono<EventPage> list(int limit, String cursor) {
        return eventRepository.findPage(limit, cursor);
    }

    public Mono<Event> update(String eventId, EventDetails details, long expectedVersion) {
        return Mono.defer(() -> eventRepository.updateDetails(eventId, details, expectedVersion, now()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
