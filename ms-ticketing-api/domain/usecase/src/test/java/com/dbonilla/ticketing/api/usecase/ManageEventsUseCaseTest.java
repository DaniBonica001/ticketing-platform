package com.dbonilla.ticketing.api.usecase;

import com.dbonilla.ticketing.api.model.event.Event;
import com.dbonilla.ticketing.api.model.event.EventDetails;
import com.dbonilla.ticketing.api.model.event.EventPage;
import com.dbonilla.ticketing.api.model.event.gateways.EventRepository;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.EventNotFoundException;
import com.dbonilla.ticketing.api.model.exception.VersionConflictException;
import com.dbonilla.ticketing.api.usecase.manageevents.ManageEventsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.List;

import static com.dbonilla.ticketing.api.usecase.Fixtures.CLOCK;
import static com.dbonilla.ticketing.api.usecase.Fixtures.DETAILS;
import static com.dbonilla.ticketing.api.usecase.Fixtures.EVENT_ID;
import static com.dbonilla.ticketing.api.usecase.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageEventsUseCaseTest {

    @Mock
    private EventRepository repository;

    @Test
    void createsEventWithRandomIdAndFullInventory() {
        when(repository.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        ManageEventsUseCase useCase = new ManageEventsUseCase(repository, CLOCK);

        StepVerifier.create(useCase.create(DETAILS, 5))
                .assertNext(event -> {
                    assertThat(event.eventId()).hasSize(36);
                    assertThat(event.inventory().available()).isEqualTo(5);
                    assertThat(event.createdAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void rejectsEventInThePastWithoutWriting() {
        ManageEventsUseCase useCase = new ManageEventsUseCase(repository, CLOCK);
        EventDetails past = new EventDetails("n", OffsetDateTime.parse("2020-01-01T00:00:00Z"), "v");

        StepVerifier.create(useCase.create(past, 5)).verifyError(DomainValidationException.class);
        verify(repository, never()).create(any());
    }

    @Test
    void getsExistingEvent() {
        Event event = Fixtures.event();
        when(repository.findById(EVENT_ID)).thenReturn(Mono.just(event));

        StepVerifier.create(new ManageEventsUseCase(repository, CLOCK).get(EVENT_ID)).expectNext(event).verifyComplete();
    }

    @Test
    void reportsMissingEvent() {
        when(repository.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(new ManageEventsUseCase(repository, CLOCK).get(EVENT_ID))
                .verifyError(EventNotFoundException.class);
    }

    @Test
    void listsAPage() {
        EventPage page = new EventPage(List.of(Fixtures.event()), "next");
        when(repository.findPage(20, "cursor")).thenReturn(Mono.just(page));

        StepVerifier.create(new ManageEventsUseCase(repository, CLOCK).list(20, "cursor")).expectNext(page).verifyComplete();
    }

    @Test
    void updatesWithExpectedVersionAndCurrentTime() {
        Event updated = Fixtures.event();
        when(repository.updateDetails(EVENT_ID, DETAILS, 3, NOW)).thenReturn(Mono.just(updated));

        StepVerifier.create(new ManageEventsUseCase(repository, CLOCK).update(EVENT_ID, DETAILS, 3))
                .expectNext(updated)
                .verifyComplete();
    }

    @Test
    void propagatesVersionConflict() {
        when(repository.updateDetails(EVENT_ID, DETAILS, 3, NOW))
                .thenReturn(Mono.error(new VersionConflictException(EVENT_ID, 3)));

        StepVerifier.create(new ManageEventsUseCase(repository, CLOCK).update(EVENT_ID, DETAILS, 3))
                .verifyError(VersionConflictException.class);
    }
}
