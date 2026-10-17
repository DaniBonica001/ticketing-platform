package com.dbonilla.ticketing.expirer.model.order;

/**
 * The event no longer holds enough reserved tickets for the order being expired. This would break the
 * inventory invariant, so the transaction is refused and the inconsistency must be investigated.
 */
public final class InventoryInvariantViolationException extends RuntimeException implements DomainError {

    private final String orderId;

    public InventoryInvariantViolationException(String orderId, String eventId) {
        super("Event " + eventId + " has fewer reserved tickets than order " + orderId);
        this.orderId = orderId;
    }

    @Override
    public String orderId() {
        return orderId;
    }
}
