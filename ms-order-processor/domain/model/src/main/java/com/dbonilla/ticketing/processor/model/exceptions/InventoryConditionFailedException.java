package com.dbonilla.ticketing.processor.model.exceptions;

/**
 * The event inventory did not satisfy the condition of the transition (not enough tickets in the source counter).
 */
public final class InventoryConditionFailedException extends OrderProcessingException {

    public InventoryConditionFailedException(String eventId) {
        super("Inventory condition failed for event " + eventId);
    }
}
