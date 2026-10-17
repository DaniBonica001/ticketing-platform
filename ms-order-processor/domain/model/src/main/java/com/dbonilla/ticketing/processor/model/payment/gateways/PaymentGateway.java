package com.dbonilla.ticketing.processor.model.payment.gateways;

import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import reactor.core.publisher.Mono;

public interface PaymentGateway {

    /**
     * Verifies a payment. Errors with
     * {@link com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException}
     * when the gateway cannot give an answer (timeout, outage, open circuit).
     */
    Mono<PaymentStatus> verify(String paymentReference);
}
