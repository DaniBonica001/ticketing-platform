package com.dbonilla.ticketing.processor.model.exceptions;

public final class MissingPaymentReferenceException extends OrderProcessingException {

    public MissingPaymentReferenceException(String orderId) {
        super("Order " + orderId + " has no payment reference");
    }
}
