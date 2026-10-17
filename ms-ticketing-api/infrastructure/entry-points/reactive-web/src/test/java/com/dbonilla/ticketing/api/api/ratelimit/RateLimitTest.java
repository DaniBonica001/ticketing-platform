package com.dbonilla.ticketing.api.api.ratelimit;

import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitTest {

    private final AtomicLong clock = new AtomicLong(0);

    @Test
    void bucketAllowsBurstThenRefillsAtTheConfiguredRate() {
        TokenBucket bucket = new TokenBucket(10, 5, 0);

        for (int i = 0; i < 10; i++) {
            assertThat(bucket.tryConsume(0).isAllowed()).isTrue();
        }
        RateLimitDecision rejected = bucket.tryConsume(0);
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMillis(200));
        assertThat(bucket.tryConsume(TimeUnit.MILLISECONDS.toNanos(200)).isAllowed()).isTrue();
        assertThat(bucket.tryConsume(TimeUnit.MILLISECONDS.toNanos(200)).isAllowed()).isFalse();
    }

    @Test
    void limiterKeepsOneBucketPerKeyWithBoundedMemory() {
        RateLimiter limiter = new RateLimiter(new RateLimitProperties.Limit(1, 1), 100, Duration.ofMinutes(1), clock::get);

        assertThat(limiter.tryAcquire("a").isAllowed()).isTrue();
        assertThat(limiter.tryAcquire("a").isAllowed()).isFalse();
        assertThat(limiter.tryAcquire("b").isAllowed()).isTrue();
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("key-" + i);
        }
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(100);
    }

    @Test
    void propertiesHaveDocumentedDefaultsAndRejectInvalidLimits() {
        RateLimitProperties defaults = new RateLimitProperties(null, null, null, null);

        assertThat(defaults.orders()).isEqualTo(new RateLimitProperties.Limit(5, 10));
        assertThat(defaults.publicEndpoints()).isEqualTo(new RateLimitProperties.Limit(50, 50));
        assertThat(defaults.maxKeys()).isEqualTo(100_000L);
        assertThat(defaults.idleTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> new RateLimitProperties.Limit(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties.Limit(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        assertThat(RateLimitFilter.retryAfter(Duration.ofMillis(10))).isEqualTo(Duration.ofSeconds(1));
        assertThat(RateLimitFilter.retryAfter(Duration.ofMillis(1500))).isEqualTo(Duration.ofSeconds(2));
        assertThat(RateLimitFilter.retryAfter(Duration.ZERO)).isEqualTo(Duration.ofSeconds(1));
    }

    private RateLimitFilter filter() {
        RateLimitProperties properties = new RateLimitProperties(new RateLimitProperties.Limit(5, 2),
                new RateLimitProperties.Limit(50, 1), 1000L, Duration.ofMinutes(1));
        return new RateLimitFilter(properties, new ProblemWriter(JsonMapper.builder().build()), clock::get);
    }

    private static WebFilterChain counting(AtomicInteger passed) {
        return exchange -> {
            passed.incrementAndGet();
            return Mono.empty();
        };
    }

    @Test
    void limitsPlaceOrderPerAuthenticatedUser() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        Principal alice = () -> "alice";

        for (int i = 0; i < 3; i++) {
            ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders"))
                    .mutate().principal(Mono.just(alice)).build();
            StepVerifier.create(filter.filter(exchange, counting(passed))).verifyComplete();
            if (i == 2) {
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
            }
        }
        assertThat(passed).hasValue(2);
    }

    @Test
    void limitsPublicEndpointsPerClientIp() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        MockServerHttpRequest first = MockServerHttpRequest.get("/api/v1/events")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234)).build();
        MockServerHttpRequest second = MockServerHttpRequest.get("/api/v1/events/x/availability")
                .remoteAddress(new InetSocketAddress("10.0.0.1", 1234)).build();
        MockServerHttpRequest otherIp = MockServerHttpRequest.get("/api/v1/events").build();

        StepVerifier.create(filter.filter(MockServerWebExchange.from(first), counting(passed))).verifyComplete();
        MockServerWebExchange limited = MockServerWebExchange.from(second);
        StepVerifier.create(filter.filter(limited, counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(otherIp), counting(passed))).verifyComplete();

        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(passed).hasValue(2);
    }

    @Test
    void otherEndpointsAreNotLimited() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            StepVerifier.create(filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/orders/x/confirm")), counting(passed))).verifyComplete();
        }
        assertThat(passed).hasValue(5);
    }

    @Test
    void anonymousOrderRequestsFallBackToTheClientIp() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/orders")), counting(passed))).verifyComplete();
        }
        assertThat(passed).hasValue(2);
    }
}
