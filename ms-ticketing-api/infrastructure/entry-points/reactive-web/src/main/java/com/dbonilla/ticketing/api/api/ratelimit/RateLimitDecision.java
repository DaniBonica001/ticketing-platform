package com.dbonilla.ticketing.api.api.ratelimit;

import java.time.Duration;

record RateLimitDecision(boolean isAllowed, Duration retryAfter) {

    private static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);

    static RateLimitDecision allowed() {
        return ALLOWED;
    }

    static RateLimitDecision rejected(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
