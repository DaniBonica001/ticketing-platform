package com.dbonilla.ticketing.processor.paymentgateway.config;

import co.com.bancolombia.secretsmanager.api.GenericManagerAsync;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentGatewayConfigTest {

    private final PaymentGatewayConfig config = new PaymentGatewayConfig();

    @Test
    void buildsResilienceComponentsFromSettings() {
        TimeLimiter timeLimiter = config.paymentGatewayTimeLimiter(Duration.ofSeconds(2));
        CircuitBreaker circuitBreaker = config.paymentGatewayCircuitBreaker(50, 10, 5, Duration.ofSeconds(30));
        Retry retry = config.paymentGatewayRetry(3, Duration.ofMillis(200));

        assertThat(timeLimiter.getTimeLimiterConfig().getTimeoutDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
        assertThat(config.paymentGateway(
                config.paymentGatewayCredentials(mock(GenericManagerAsync.class), "id", Duration.ofHours(1)),
                timeLimiter, circuitBreaker, retry)).isNotNull();
    }
}
