package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.event.Availability;
import com.dbonilla.ticketing.api.model.event.Inventory;
import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.usecase.getavailability.GetAvailabilityUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static com.dbonilla.ticketing.api.usecase.Fixtures.CLOCK;
import static com.dbonilla.ticketing.api.usecase.Fixtures.EVENT_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.NOW;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailabilityUseCaseTest {

    private static final Inventory FULL = Inventory.initial(5);
    private static final Inventory ONE_RESERVED = new Inventory(5, 4, 1, 0, 0, 0);

    @Mock
    private EventRepository repository;

    @Test
    void returnsSnapshotOfTheInventory() {
        when(repository.findById(EVENT_ID)).thenReturn(Mono.just(Fixtures.event(FULL)));

        StepVerifier.create(new GetAvailabilityUseCase(repository, CLOCK).get(EVENT_ID))
                .expectNext(new Availability(EVENT_ID, FULL, NOW))
                .verifyComplete();
    }

    @Test
    void reportsMissingEvent() {
        when(repository.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetAvailabilityUseCase(repository, CLOCK).get(EVENT_ID))
                .verifyError(EventNotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamEmitsOnlyChangesEveryTwoSecondsAndClosesAfterFiveMinutes() {
        when(repository.findById(EVENT_ID)).thenReturn(
                Mono.just(Fixtures.event(FULL)),
                Mono.just(Fixtures.event(ONE_RESERVED)),
                Mono.just(Fixtures.event(ONE_RESERVED)),
                Mono.just(Fixtures.event(FULL)));
        GetAvailabilityUseCase useCase = new GetAvailabilityUseCase(repository, CLOCK);
        Availability initial = new Availability(EVENT_ID, FULL, NOW);

        StepVerifier.withVirtualTime(() -> useCase.stream(initial))
                .expectSubscription()
                .expectNext(initial)
                .expectNoEvent(Duration.ofSeconds(2))
                .thenAwait(Duration.ofSeconds(2))
                .expectNextMatches(availability -> availability.inventory().equals(ONE_RESERVED))
                .expectNoEvent(Duration.ofSeconds(3))
                .thenAwait(Duration.ofSeconds(1))
                .expectNextMatches(availability -> availability.inventory().equals(FULL))
                .thenAwait(GetAvailabilityUseCase.MAX_STREAM_DURATION)
                .verifyComplete();
    }
}
