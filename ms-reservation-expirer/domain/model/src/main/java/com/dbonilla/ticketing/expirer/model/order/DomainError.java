package com.dbonilla.ticketing.expirer.model.order;

/**
 * Typed business errors of the expirer bounded context.
 */
public sealed interface DomainError permits InventoryInvariantViolationException {

    String orderId();
}
