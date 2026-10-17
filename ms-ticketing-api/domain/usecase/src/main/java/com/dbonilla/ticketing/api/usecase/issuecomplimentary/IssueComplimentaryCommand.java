package com.dbonilla.ticketing.api.usecase.issuecomplimentary;

import com.dbonilla.ticketing.api.model.order.Caller;

public record IssueComplimentaryCommand(Caller caller, String idempotencyKey, String eventId, int quantity,
                                        String recipientUserId) {

    /** Canonical payload hashed into {@code requestHash}. */
    public String canonicalPayload() {
        return "complimentary:" + eventId + ":" + quantity + ":" + recipientUserId;
    }
}
