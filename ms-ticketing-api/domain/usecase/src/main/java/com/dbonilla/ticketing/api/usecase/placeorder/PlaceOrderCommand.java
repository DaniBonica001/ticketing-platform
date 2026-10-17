package com.dbonilla.ticketing.api.usecase.placeorder;

import com.dbonilla.ticketing.api.model.order.Caller;

public record PlaceOrderCommand(Caller caller, String idempotencyKey, String eventId, int quantity) {

    /** Canonical payload hashed into {@code requestHash} (ADR-0004). */
    public String canonicalPayload() {
        return eventId + ":" + quantity;
    }
}
