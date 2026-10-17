package com.dbonilla.ticketing.api.api;

import com.dbonilla.ticketing.api.model.event.EventPage;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.exception.VersionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRoutesTest extends WebTestBase {

    private static final String CREATE_BODY = """
            {"name":"Concert","eventDate":"2030-12-20T20:00:00-05:00","venue":"Stadium","capacity":5}""";
    private static final String UPDATE_BODY = """
            {"name":"Concert","eventDate":"2030-12-20T20:00:00-05:00","venue":"Stadium"}""";

    @Test
    void listIsPublicAndPaginated() {
        when(manageEvents.list(20, null)).thenReturn(Mono.just(new EventPage(List.of(event()), "next")));

        client.get().uri("/api/v1/events").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().exists("Strict-Transport-Security")
                .expectBody()
                .jsonPath("$.items[0].eventId").isEqualTo(EVENT_ID)
                .jsonPath("$.items[0].eventDate").isEqualTo("2026-12-20T20:00:00-05:00")
                .jsonPath("$.items[0].availability.available").isEqualTo(3)
                .jsonPath("$.items[0].createdAt").isEqualTo("2026-10-16T10:00:00.123Z")
                .jsonPath("$.nextCursor").isEqualTo("next");
    }

    @Test
    void listForwardsLimitAndCursor() {
        when(manageEvents.list(5, "abc")).thenReturn(Mono.just(new EventPage(List.of(), null)));

        client.get().uri("/api/v1/events?limit=5&cursor=abc").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items").isEmpty();
    }

    @Test
    void listRejectsInvalidLimitAndCursor() {
        client.get().uri("/api/v1/events?limit=0").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("limit");
        client.get().uri("/api/v1/events?limit=abc").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/events?limit=101").exchange().expectStatus().isBadRequest();
        client.get().uri("/api/v1/events?cursor=" + "a".repeat(513)).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("cursor");
    }

    @Test
    void invalidCursorFromStorageIsBadRequest() {
        when(manageEvents.list(anyInt(), any())).thenReturn(Mono.error(new DomainValidationException("cursor", "bad")));

        client.get().uri("/api/v1/events?cursor=zzz").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.errors[0].field").isEqualTo("cursor");
    }

    @Test
    void adminCreatesEvent() {
        when(manageEvents.create(any(), eq(5))).thenReturn(Mono.just(event()));

        admin().post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON).bodyValue(CREATE_BODY).exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/events/" + EVENT_ID)
                .expectHeader().valueEquals("ETag", "\"3\"")
                .expectBody().jsonPath("$.version").isEqualTo(3);
    }

    @Test
    void createValidatesTheBody() {
        admin().post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"\",\"eventDate\":\"2020-01-01T00:00:00Z\",\"venue\":\"v\",\"capacity\":0}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.type").isEqualTo("https://ticketing.local/problems/validation-error")
                .jsonPath("$.errors.length()").isEqualTo(3)
                .jsonPath("$.traceId").exists();
        verify(manageEvents, never()).create(any(), anyInt());
    }

    @Test
    void createRejectsUnknownPropertiesAndMissingBody() {
        admin().post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(CREATE_BODY.replace("}", ",\"extra\":1}")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("The request is malformed");
        admin().post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("body");
    }

    @Test
    void createRequiresAdminScope() {
        user().post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON).bodyValue(CREATE_BODY).exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON).bodyValue(CREATE_BODY).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void getReturnsEventWithEtag() {
        when(manageEvents.get(EVENT_ID)).thenReturn(Mono.just(event()));

        client.get().uri("/api/v1/events/" + EVENT_ID.toUpperCase()).exchange()
                .expectStatus().isOk();
        client.get().uri("/api/v1/events/" + EVENT_ID).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"3\"")
                .expectBody().jsonPath("$.name").isEqualTo("Concert");
    }

    @Test
    void headOnPublicEventIsAllowedAndExposesEtag() {
        when(manageEvents.get(EVENT_ID)).thenReturn(Mono.just(event()));

        client.head().uri("/api/v1/events/" + EVENT_ID).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"3\"");
    }

    @Test
    void getUnknownEventIsNotFound() {
        when(manageEvents.get(EVENT_ID)).thenReturn(Mono.error(new EventNotFoundException(EVENT_ID)));

        client.get().uri("/api/v1/events/" + EVENT_ID).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("EVENT_NOT_FOUND")
                .jsonPath("$.instance").isEqualTo("/api/v1/events/" + EVENT_ID);
    }

    @Test
    void getRejectsInvalidId() {
        client.get().uri("/api/v1/events/not-a-uuid").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("eventId");
    }

    @Test
    void updateUsesIfMatchVersion() {
        when(manageEvents.update(eq(EVENT_ID), any(), eq(3L))).thenReturn(Mono.just(event()));

        admin().put().uri("/api/v1/events/" + EVENT_ID).header("If-Match", "\"3\"")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(UPDATE_BODY).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("ETag", "\"3\"");
        admin().put().uri("/api/v1/events/" + EVENT_ID).header("If-Match", "W/\"3\"")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(UPDATE_BODY).exchange()
                .expectStatus().isOk();
    }

    @Test
    void updateWithoutIfMatchIsPreconditionRequired() {
        admin().put().uri("/api/v1/events/" + EVENT_ID).contentType(MediaType.APPLICATION_JSON).bodyValue(UPDATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(428)
                .expectBody().jsonPath("$.code").isEqualTo("PRECONDITION_REQUIRED");
    }

    @Test
    void updateWithMalformedIfMatchIsBadRequest() {
        admin().put().uri("/api/v1/events/" + EVENT_ID).header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(UPDATE_BODY).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("If-Match");
    }

    @Test
    void updateWithStaleVersionIsPreconditionFailed() {
        when(manageEvents.update(eq(EVENT_ID), any(), eq(2L)))
                .thenReturn(Mono.error(new VersionConflictException(EVENT_ID, 2)));

        admin().put().uri("/api/v1/events/" + EVENT_ID).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(UPDATE_BODY).exchange()
                .expectStatus().isEqualTo(412)
                .expectBody().jsonPath("$.code").isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void unavailableDependencyIsServiceUnavailableWithRetryAfter() {
        when(manageEvents.get(EVENT_ID)).thenReturn(Mono.error(new ServiceUnavailableException("DynamoDB",
                new IllegalStateException("secret internal detail"))));

        client.get().uri("/api/v1/events/" + EVENT_ID).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "5")
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.detail").value(detail -> org.assertj.core.api.Assertions.assertThat((String) detail)
                        .doesNotContain("secret").doesNotContain("DynamoDB"));
    }

    @Test
    void unexpectedErrorsNeverLeakInternals() {
        when(manageEvents.get(EVENT_ID)).thenReturn(Mono.error(new IllegalStateException("stack secret")));

        client.get().uri("/api/v1/events/" + EVENT_ID).exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.detail").isEqualTo("An unexpected error occurred");
    }
}
