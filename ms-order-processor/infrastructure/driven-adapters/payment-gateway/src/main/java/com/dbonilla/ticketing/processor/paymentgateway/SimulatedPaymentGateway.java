package com.dbonilla.ticketing.processor.paymentgateway;

import com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException;
import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import com.dbonilla.ticketing.processor.model.payment.gateways.PaymentGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;

/**
 * Simulated payment gateway. Decision by reference prefix:
 * {@code DECLINED-} declined, {@code UNAVAILABLE-} simulated outage, {@code SLOW-} answer slower than the timeout,
 * anything else approved. Every call is protected by time limiter, circuit breaker and retry (Resilience4j).
 */
public class SimulatedPaymentGateway implements PaymentGateway {

    static final String DECLINED_PREFIX = "DECLINED-";
    static final String UNAVAILABLE_PREFIX = "UNAVAILABLE-";
    static final String SLOW_PREFIX = "SLOW-";
    private static final Duration SLOW_RESPONSE = Duration.ofSeconds(30);

    private final PaymentGatewayCredentials credentials;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public SimulatedPaymentGateway(PaymentGatewayCredentials credentials, TimeLimiter timeLimiter,
                                   CircuitBreaker circuitBreaker, Retry retry) {
        this.credentials = credentials;
        this.timeLimiter = timeLimiter;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Mono<PaymentStatus> verify(String paymentReference) {
        return credentials.apiKey()
                .flatMap(apiKey -> simulate(paymentReference))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorMap(SimulatedPaymentGateway::toUnavailable);
    }

    private static Mono<PaymentStatus> simulate(String paymentReference) {
        if (paymentReference.startsWith(UNAVAILABLE_PREFIX)) {
            return Mono.error(new IOException("Simulated payment gateway outage"));
        }
        if (paymentReference.startsWith(SLOW_PREFIX)) {
            return Mono.delay(SLOW_RESPONSE).thenReturn(PaymentStatus.APPROVED);
        }
        return Mono.just(paymentReference.startsWith(DECLINED_PREFIX) ? PaymentStatus.DECLINED : PaymentStatus.APPROVED);
    }

    private static Throwable toUnavailable(Throwable error) {
        String message = error instanceof CallNotPermittedException
                ? "Payment gateway circuit breaker is open"
                : "Payment gateway did not answer: " + error.getClass().getSimpleName();
        return new PaymentGatewayUnavailableException(message, error);
    }
}
