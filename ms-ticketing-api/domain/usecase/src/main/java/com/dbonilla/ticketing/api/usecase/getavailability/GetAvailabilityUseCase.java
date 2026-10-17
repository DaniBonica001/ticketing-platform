package com.dbonilla.ticketing.api.usecase.getavailability;

import com.dbonilla.ticketing.api.model.event.Availability;
import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Strongly consistent availability snapshots, either once or as a polled stream.
 */
public class GetAvailabilityUseCase {

    public static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    public static final Duration MAX_STREAM_DURATION = Duration.ofMinutes(5);

    private final EventRepository eventRepository;
    private final Clock clock;

    public GetAvailabilityUseCase(EventRepository eventRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public Mono<Availability> get(String eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId)))
                .map(event -> event.availability(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
    }

    /**
     * Emits {@code initial} and then a new snapshot every time the counters change (polled every
     * {@link #POLL_INTERVAL}). Completes after {@link #MAX_STREAM_DURATION}.
     */
    public Flux<Availability> stream(Availability initial) {
        return Flux.interval(POLL_INTERVAL, POLL_INTERVAL)
                .onBackpressureDrop()
                .concatMap(tick -> get(initial.eventId()))
                .startWith(initial)
                .distinctUntilChanged(Availability::inventory)
                .take(MAX_STREAM_DURATION);
    }
}
