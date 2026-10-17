package com.dbonilla.ticketing.api.api.handler;

import com.dbonilla.ticketing.api.api.dto.CreateEventRequest;
import com.dbonilla.ticketing.api.api.dto.EventPageResponse;
import com.dbonilla.ticketing.api.api.dto.EventResponse;
import com.dbonilla.ticketing.api.api.dto.UpdateEventRequest;
import com.dbonilla.ticketing.api.api.support.RequestParams;
import com.dbonilla.ticketing.api.api.support.RequestValidator;
import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.usecase.manageevents.ManageEventsUseCase;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

@Component
public class EventHandler {

    static final String EVENTS_PATH = "/api/v1/events/";

    private final ManageEventsUseCase useCase;
    private final RequestValidator validator;

    public EventHandler(ManageEventsUseCase useCase, RequestValidator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return Mono.fromCallable(() -> new PageQuery(RequestParams.limit(request), RequestParams.cursor(request)))
                .flatMap(query -> useCase.list(query.limit(), query.cursor()))
                .flatMap(page -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(EventPageResponse.from(page)));
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return validator.body(request, CreateEventRequest.class)
                .flatMap(body -> useCase.create(body.details(), body.capacity()))
                .flatMap(event -> ServerResponse.created(URI.create(EVENTS_PATH + event.eventId()))
                        .eTag(EventResponse.etag(event))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "eventId"))
                .flatMap(useCase::get)
                .flatMap(EventHandler::ok);
    }

    public Mono<ServerResponse> update(ServerRequest request) {
        return Mono.fromCallable(() -> new UpdateTarget(RequestParams.uuidPath(request, "eventId"),
                        RequestParams.expectedVersion(request)))
                .flatMap(target -> validator.body(request, UpdateEventRequest.class)
                        .flatMap(body -> useCase.update(target.eventId(), body.details(), target.version())))
                .flatMap(EventHandler::ok);
    }

    private static Mono<ServerResponse> ok(Event event) {
        return ServerResponse.ok()
                .eTag(EventResponse.etag(event))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(EventResponse.from(event));
    }

    private record UpdateTarget(String eventId, long version) {
    }

    private record PageQuery(int limit, String cursor) {
    }
}
