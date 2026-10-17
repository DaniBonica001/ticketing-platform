package com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders;

import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;

import java.util.List;
import java.util.stream.Stream;

/**
 * Counts of one reconciliation run.
 */
public record ReconciliationResult(int republishedQueued, int republishedPendingConfirmation,
                                   List<OrderFailure> failures) {

    public static final ReconciliationResult EMPTY = new ReconciliationResult(0, 0, List.of());

    public ReconciliationResult {
        failures = List.copyOf(failures);
    }

    static ReconciliationResult republished(ReconciliationTarget target) {
        return switch (target) {
            case QUEUED -> new ReconciliationResult(1, 0, List.of());
            case PENDING_CONFIRMATION -> new ReconciliationResult(0, 1, List.of());
        };
    }

    static ReconciliationResult failed(OrderFailure failure) {
        return new ReconciliationResult(0, 0, List.of(failure));
    }

    public ReconciliationResult plus(ReconciliationResult other) {
        return new ReconciliationResult(republishedQueued + other.republishedQueued,
                republishedPendingConfirmation + other.republishedPendingConfirmation,
                Stream.concat(failures.stream(), other.failures.stream()).toList());
    }
}
