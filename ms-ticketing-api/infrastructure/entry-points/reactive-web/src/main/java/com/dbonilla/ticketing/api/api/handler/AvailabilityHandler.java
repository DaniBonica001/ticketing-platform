package com.dbonilla.ticketing.api.api.handler;

import com.dbonilla.ticketing.api.api.dto.AvailabilityResponse;
import com.dbonilla.ticketing.api.api.support.RequestParams;
import com.dbonilla.ticketing.api.usecase.getavailability.GetAvailabilityUseCase;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public class AvailabilityHandler {

    private final GetAvailabilityUseCase useCase;

    public AvailabilityHandler(GetAvailabilityUseCase useCase) {
        this.useCase = useCase;
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "eventId"))
                .flatMap(useCase::get)
                .flatMap(availability -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(AvailabilityResponse.from(availability)));
    }

    /** The first snapshot is read before answering so that an unknown event is a plain 404. */
    public Mono<ServerResponse> stream(ServerRequest request) {
        return Mono.fromCallable(() -> RequestParams.uuidPath(request, "eventId"))
                .flatMap(useCase::get)
                .flatMap(initial -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(BodyInserters.fromServerSentEvents(
                                AvailabilityStream.withHeartbeat(useCase.stream(initial)))));
    }
}
