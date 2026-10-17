package com.dbonilla.ticketing.processor.model.processing;

import java.time.Duration;
import java.util.Objects;

/**
 * @param reservationTtl time the user has to confirm a reservation (10 minutes)
 */
public record ReservationSettings(Duration reservationTtl) {

    public ReservationSettings {
        Objects.requireNonNull(reservationTtl, "reservationTtl");
        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }
}
