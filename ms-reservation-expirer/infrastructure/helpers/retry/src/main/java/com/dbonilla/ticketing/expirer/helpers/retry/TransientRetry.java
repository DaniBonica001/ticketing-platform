package com.dbonilla.ticketing.expirer.helpers.retry;

import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

/**
 * {@code Retry.backoff(3, 100ms).maxBackoff(2s).jitter(0.5).filter(isTransient)}; when the attempts are
 * exhausted the original error is propagated (not Reactor's wrapper) so callers can classify it.
 */
public final class TransientRetry {

    private TransientRetry() {
    }

    public static RetryBackoffSpec backoff(RetrySettings settings) {
        return Retry.backoff(settings.maxRetries(), settings.minBackoff())
                .maxBackoff(settings.maxBackoff())
                .jitter(settings.jitter())
                .filter(TransientErrors::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
