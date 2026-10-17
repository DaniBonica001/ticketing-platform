package com.dbonilla.ticketing.processor.paymentgateway;

import com.dbonilla.ticketing.processor.awserrors.TransientErrors;
import com.dbonilla.ticketing.processor.model.exceptions.PaymentGatewayUnavailableException;
import com.dbonilla.ticketing.processor.model.payment.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulatedPaymentGatewayTest {

    private final PaymentGatewayCredentials credentials = mock(PaymentGatewayCredentials.class);
    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private SimulatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        when(credentials.apiKey()).thenReturn(Mono.just(new ApiKey("k")));
        gateway = gatewayWithMinimumCalls(10);
    }

    private SimulatedPaymentGateway gatewayWithMinimumCalls(int minimumCalls) {
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(minimumCalls).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1)).build());
        retry = Retry.of("test", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1))
                .retryOnException(TransientErrors::isTransient).build());
        TimeLimiter timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(200)).build());
        return new SimulatedPaymentGateway(credentials, timeLimiter, circuitBreaker, retry);
    }

    @Test
    void approvesRegularReferences() {
        StepVerifier.create(gateway.verify("PAY-123")).expectNext(PaymentStatus.APPROVED).verifyComplete();
    }

    @Test
    void declinesReferencesWithDeclinedPrefix() {
        StepVerifier.create(gateway.verify("DECLINED-123")).expectNext(PaymentStatus.DECLINED).verifyComplete();
    }

    @Test
    void retriesOutagesAndReportsTheGatewayUnavailable() {
        StepVerifier.create(gateway.verify("UNAVAILABLE-1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayUnavailableException.class)
                        .hasCauseInstanceOf(IOException.class))
                .verify();
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void timesOutSlowAnswers() {
        StepVerifier.create(gateway.verify("SLOW-1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayUnavailableException.class)
                        .hasCauseInstanceOf(TimeoutException.class))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void openCircuitRejectsCallsWithoutCallingTheGateway() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act & Assert
        StepVerifier.create(gateway.verify("PAY-1"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(PaymentGatewayUnavailableException.class)
                        .hasMessageContaining("circuit breaker is open")
                        .hasCauseInstanceOf(CallNotPermittedException.class))
                .verify();
    }

    @Test
    void repeatedOutagesOpenTheCircuit() {
        gateway = gatewayWithMinimumCalls(2);

        StepVerifier.create(gateway.verify("UNAVAILABLE-1")).expectError().verify();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void missingCredentialsMakeTheGatewayUnavailable() {
        when(credentials.apiKey()).thenReturn(Mono.error(new IllegalStateException("no secret")));

        StepVerifier.create(gateway.verify("PAY-1"))
                .expectError(PaymentGatewayUnavailableException.class)
                .verify();
    }
}
