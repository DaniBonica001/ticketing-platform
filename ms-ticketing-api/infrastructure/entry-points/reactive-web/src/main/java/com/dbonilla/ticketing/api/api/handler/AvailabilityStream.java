package com.dbonilla.ticketing.api.api.handler;

import com.dbonilla.ticketing.api.api.dto.AvailabilityResponse;
import com.dbonilla.ticketing.api.model.event.Availability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * SSE framing: one {@code availability} event per change plus a heartbeat comment every 15 s. The
 * heartbeat stops when the availability stream completes (after 5 min) or fails.
 */
final class AvailabilityStream {

    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    static final String EVENT_NAME = "availability";
    static final String HEARTBEAT_COMMENT = "heartbeat";
    private static final Logger log = LoggerFactory.getLogger(AvailabilityStream.class);

    private AvailabilityStream() {
    }

    static Flux<ServerSentEvent<AvailabilityResponse>> withHeartbeat(Flux<Availability> availability) {
        Flux<ServerSentEvent<AvailabilityResponse>> events = availability
                .map(snapshot -> ServerSentEvent.builder(AvailabilityResponse.from(snapshot))
                        .id(snapshot.asOf().toString())
                        .event(EVENT_NAME)
                        .build())
                .onErrorResume(error -> {
                    log.warn("Availability stream stopped; the client is expected to reconnect", error);
                    return Flux.empty();
                });
        return events.publish(shared -> Flux.merge(shared, heartbeat().takeUntilOther(shared.then())));
    }

    private static Flux<ServerSentEvent<AvailabilityResponse>> heartbeat() {
        return Flux.interval(HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL)
                .map(tick -> ServerSentEvent.<AvailabilityResponse>builder().comment(HEARTBEAT_COMMENT).build());
    }
}
