package com.dbonilla.ticketing.processor.model.exceptions;

/**
 * The payment gateway could not give an answer (timeout, outage or open circuit breaker).
 */
public final class PaymentGatewayUnavailableException extends OrderProcessingException {

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
