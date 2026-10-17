package com.dbonilla.ticketing.api.model.event;

import com.dbonilla.ticketing.api.model.TestData;
import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.of(2026, 12, 20, 20, 0, 0, 0, ZoneOffset.ofHours(-5));

    @Test
    void createsEventAtFirstVersionWithFullInventory() {
        Event event = Event.create("e1", new EventDetails("Concert", FUTURE, "Stadium"), 5, TestData.NOW);

        assertThat(event.version()).isEqualTo(Event.INITIAL_VERSION);
        assertThat(event.inventory()).isEqualTo(Inventory.initial(5));
        assertThat(event.createdAt()).isEqualTo(TestData.NOW);
        assertThat(event.availability(TestData.NOW)).isEqualTo(new Availability("e1", event.inventory(), TestData.NOW));
    }

    @Test
    void rejectsEventInThePast() {
        EventDetails details = new EventDetails("Concert", OffsetDateTime.parse("2020-01-01T00:00:00Z"), "Stadium");

        assertThatThrownBy(() -> Event.create("e1", details, 5, TestData.NOW))
                .isInstanceOf(DomainValidationException.class)
                .extracting("field").isEqualTo("eventDate");
    }

    @Test
    void validatesDetails() {
        assertThatThrownBy(() -> new EventDetails(" ", FUTURE, "v")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new EventDetails(null, FUTURE, "v")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new EventDetails("n", FUTURE, "x".repeat(121)))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new EventDetails("n", null, "v")).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void requiresMandatoryFields() {
        EventDetails details = new EventDetails("n", FUTURE, "v");
        Inventory inventory = Inventory.initial(1);

        assertThatThrownBy(() -> new Event(null, details, inventory, 1, TestData.NOW, TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Event("e", null, inventory, 1, TestData.NOW, TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Event("e", details, null, 1, TestData.NOW, TestData.NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Event("e", details, inventory, 1, null, TestData.NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void eventPageIsAnImmutableCopy() {
        List<Event> items = new ArrayList<>();
        items.add(Event.create("e1", new EventDetails("n", FUTURE, "v"), 1, TestData.NOW));

        EventPage page = new EventPage(items, "next");
        items.clear();

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isEqualTo("next");
        assertThatThrownBy(() -> page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
