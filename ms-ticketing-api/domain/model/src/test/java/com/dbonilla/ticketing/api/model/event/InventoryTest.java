package com.dbonilla.ticketing.api.model.event;

import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    @Test
    void initialInventoryHasEveryTicketAvailable() {
        Inventory inventory = Inventory.initial(5);

        assertThat(inventory).isEqualTo(new Inventory(5, 5, 0, 0, 0, 0));
    }

    @Test
    void rejectsCapacityOutOfRange() {
        assertThatThrownBy(() -> Inventory.initial(0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Inventory.initial(100_001)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rejectsNegativeCounters() {
        assertThatThrownBy(() -> new Inventory(5, 6, -1, 0, 0, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(5, 5, 0, -1, 1, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(5, 5, 0, 0, -1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(5, 5, 1, 0, 0, -1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(5, -1, 6, 0, 0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCountersThatDoNotAddUpToCapacity() {
        assertThatThrownBy(() -> new Inventory(5, 1, 1, 1, 1, 0)).isInstanceOf(IllegalStateException.class);
    }
}
