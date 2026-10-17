package com.dbonilla.ticketing.processor.model.exceptions;

/**
 * Closed hierarchy of the errors the processor knows how to handle.
 */
public abstract sealed class OrderProcessingException extends RuntimeException
        permits OrderNotFoundException, InvalidOrderStateException, OrderStateConflictException,
        InventoryConditionFailedException, MissingPaymentReferenceException, TransientFailureException,
        PaymentGatewayUnavailableException {

    protected OrderProcessingException(String message) {
        super(message);
    }

    protected OrderProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
