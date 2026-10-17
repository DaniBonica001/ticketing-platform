package com.dbonilla.ticketing.processor.sqs.listener;

/**
 * Why a message could not be processed. Every failure keeps the message for redelivery (then DLQ); the name is the
 * {@code outcome} tag of the processing metric.
 */
enum FailureOutcome {
    INVALID_MESSAGE(false),
    TRANSIENT_FAILURE(false),
    PAYMENT_GATEWAY_UNAVAILABLE(false),
    ORDER_NOT_FOUND(false),
    /** The per-message deadline elapsed before the use case finished; the in-flight work is cancelled. */
    PROCESSING_TIMEOUT(false),
    INVENTORY_INCONSISTENT(true),
    UNEXPECTED_ERROR(true);

    private final boolean unexpected;

    FailureOutcome(boolean unexpected) {
        this.unexpected = unexpected;
    }

    /** Whether the failure points to a bug or a data inconsistency (logged as error) rather than an expected condition. */
    boolean isUnexpected() {
        return unexpected;
    }
}
