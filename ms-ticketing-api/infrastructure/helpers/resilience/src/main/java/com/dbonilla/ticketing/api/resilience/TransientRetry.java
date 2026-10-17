package com.dbonilla.ticketing.api.resilience;

import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

/**
 * {@code Retry.backoff(3, 100ms).maxBackoff(2s).jitter(0.5).filter(isTransient)}. When the attempts are
 * exhausted the last error is propagated unchanged so that callers can still classify it.
 */
@Component
public class TransientRetry {

    private final RetryProperties properties;

    public TransientRetry(RetryProperties properties) {
        this.properties = properties;
    }

    public RetryBackoffSpec spec() {
        return Retry.backoff(properties.maxAttempts(), properties.minBackoff())
                .maxBackoff(properties.maxBackoff())
                .jitter(properties.jitter())
                .filter(TransientErrors::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
