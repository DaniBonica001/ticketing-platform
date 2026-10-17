package com.dbonilla.ticketing.processor.config;

import com.dbonilla.ticketing.processor.model.processing.ReservationSettings;
import com.dbonilla.ticketing.processor.model.processing.RetryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ProcessingConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy(@Value("${ticketing.retry.max-retries:3}") long maxRetries,
                                   @Value("${ticketing.retry.min-backoff:PT0.1S}") Duration minBackoff,
                                   @Value("${ticketing.retry.max-backoff:PT2S}") Duration maxBackoff,
                                   @Value("${ticketing.retry.jitter:0.5}") double jitter) {
        return new RetryPolicy(maxRetries, minBackoff, maxBackoff, jitter);
    }

    @Bean
    public ReservationSettings reservationSettings(@Value("${ticketing.reservation.ttl:PT10M}") Duration ttl) {
        return new ReservationSettings(ttl);
    }
}
