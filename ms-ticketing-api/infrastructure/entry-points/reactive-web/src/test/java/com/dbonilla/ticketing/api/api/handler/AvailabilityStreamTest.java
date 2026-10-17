package com.dbonilla.ticketing.api.api.handler;

import com.dbonilla.ticketing.api.model.event.Availability;
import com.dbonilla.ticketing.api.model.event.Inventory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

class AvailabilityStreamTest {

    private static final Availability SNAPSHOT =
            new Availability("e1", Inventory.initial(5), Instant.parse("2026-10-16T10:00:00Z"));

    @Test
    void sendsHeartbeatEveryFifteenSecondsUntilTheStreamEnds() {
        StepVerifier.withVirtualTime(() -> AvailabilityStream.withHeartbeat(
                        Flux.just(SNAPSHOT).concatWith(Flux.<Availability>never().take(Duration.ofSeconds(40)))))
                .expectNextMatches(event -> AvailabilityStream.EVENT_NAME.equals(event.event())
                        && event.data().available() == 5)
                .thenAwait(Duration.ofSeconds(15))
                .expectNextMatches(event -> AvailabilityStream.HEARTBEAT_COMMENT.equals(event.comment()))
                .thenAwait(Duration.ofSeconds(15))
                .expectNextMatches(event -> event.comment() != null)
                .thenAwait(Duration.ofSeconds(10))
                .verifyComplete();
    }

    @Test
    void endsQuietlyWhenPollingFails() {
        StepVerifier.create(AvailabilityStream.withHeartbeat(
                        Flux.just(SNAPSHOT).concatWith(Flux.error(new IllegalStateException("dynamo down")))))
                .expectNextCount(1)
                .verifyComplete();
    }
}
