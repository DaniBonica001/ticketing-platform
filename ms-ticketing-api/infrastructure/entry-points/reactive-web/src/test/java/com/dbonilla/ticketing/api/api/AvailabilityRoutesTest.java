package com.dbonilla.ticketing.api.api;

import com.dbonilla.ticketing.api.model.event.Availability;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.Mockito.when;

class AvailabilityRoutesTest extends WebTestBase {

    private static final Availability SNAPSHOT = new Availability(EVENT_ID, new Inventory(5, 3, 1, 0, 1, 0), NOW);

    @Test
    void availabilityIsPublic() {
        when(availability.get(EVENT_ID)).thenReturn(Mono.just(SNAPSHOT));

        client.get().uri("/api/v1/events/" + EVENT_ID + "/availability").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.available").isEqualTo(3)
                .jsonPath("$.reserved").isEqualTo(1)
                .jsonPath("$.sold").isEqualTo(1)
                .jsonPath("$.asOf").isEqualTo("2026-10-16T10:00:00.123Z");
    }

    @Test
    void unknownEventIsNotFound() {
        when(availability.get(EVENT_ID)).thenReturn(Mono.error(new EventNotFoundException(EVENT_ID)));

        client.get().uri("/api/v1/events/" + EVENT_ID + "/availability").exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/events/" + EVENT_ID + "/availability/stream").exchange().expectStatus().isNotFound();
    }

    @Test
    void streamSendsAvailabilityEvents() {
        Availability changed = new Availability(EVENT_ID, new Inventory(5, 2, 2, 0, 1, 0), NOW.plusSeconds(2));
        when(availability.get(EVENT_ID)).thenReturn(Mono.just(SNAPSHOT));
        when(availability.stream(SNAPSHOT)).thenReturn(Flux.just(SNAPSHOT, changed));

        Flux<ServerSentEvent<Map>> events = client.get().uri("/api/v1/events/" + EVENT_ID + "/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new org.springframework.core.ParameterizedTypeReference<ServerSentEvent<Map>>() {
                })
                .getResponseBody();

        StepVerifier.create(events)
                .expectNextMatches(event -> "availability".equals(event.event()) && event.data().get("available").equals(3))
                .expectNextMatches(event -> event.data().get("available").equals(2))
                .verifyComplete();
    }
}
