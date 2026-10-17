package com.dbonilla.ticketing.api.api.ratelimit;

import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitTest {

    private static final RateLimitProperties.Limit UNLIMITED = new RateLimitProperties.Limit(10_000, 10_000);

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
        RateLimitProperties defaults = new RateLimitProperties(null, null, null, null, null, null, null, null, null);

        assertThat(defaults.orders()).isEqualTo(new RateLimitProperties.Limit(5, 10));
        assertThat(defaults.publicEndpoints()).isEqualTo(new RateLimitProperties.Limit(50, 50));
        assertThat(defaults.maxKeys()).isEqualTo(100_000L);
        assertThat(defaults.idleTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.trustedProxies()).as("no proxy is trusted unless configured").isEmpty();
        assertThat(defaults.clientIpHeader()).isEqualTo("X-Client-Ip");
        assertThat(defaults.confirm()).isEqualTo(new RateLimitProperties.Limit(2, 5));
        assertThat(defaults.adminOperations()).isEqualTo(new RateLimitProperties.Limit(5, 10));
        assertThat(defaults.availabilityStreams()).isEqualTo(new RateLimitProperties.StreamLimit(3, 1000));
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
        return filter(new RateLimitProperties.Limit(50, 1), new RateLimitProperties.StreamLimit(2, 3));
    }

    private RateLimitFilter filter(RateLimitProperties.Limit publicLimit, RateLimitProperties.StreamLimit streams) {
        RateLimitProperties properties = new RateLimitProperties(new RateLimitProperties.Limit(5, 2), publicLimit,
                1000L, Duration.ofMinutes(1), List.of("10.0.0.0/16"), null, new RateLimitProperties.Limit(2, 2),
                new RateLimitProperties.Limit(5, 2), streams);
        return new RateLimitFilter(properties, new ProblemWriter(JsonMapper.builder().build()), clock::get);
    }

    private static ServerWebExchange as(String user, MockServerHttpRequest.BaseBuilder<?> request) {
        Principal principal = () -> user;
        return MockServerWebExchange.from(request).mutate().principal(Mono.just(principal)).build();
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
    void clientsBehindTheTrustedLoadBalancerGetTheirOwnBucket() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        InetSocketAddress loadBalancer = new InetSocketAddress("10.0.5.5", 1234);
        MockServerHttpRequest abuser = MockServerHttpRequest.get("/api/v1/events").remoteAddress(loadBalancer)
                .header("X-Client-Ip", "198.51.100.1").build();
        MockServerHttpRequest abuserAgain = MockServerHttpRequest.get("/api/v1/events").remoteAddress(loadBalancer)
                .header("X-Client-Ip", "198.51.100.1").build();
        MockServerHttpRequest otherClient = MockServerHttpRequest.get("/api/v1/events").remoteAddress(loadBalancer)
                .header("X-Client-Ip", "198.51.100.2").build();

        StepVerifier.create(filter.filter(MockServerWebExchange.from(abuser), counting(passed))).verifyComplete();
        MockServerWebExchange limited = MockServerWebExchange.from(abuserAgain);
        StepVerifier.create(filter.filter(limited, counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(MockServerWebExchange.from(otherClient), counting(passed))).verifyComplete();

        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(passed).hasValue(2);
    }

    @Test
    void trustedProxiesBindFromACommaSeparatedValue() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.rate-limit.trusted-proxies", "10.0.0.0/20,10.0.16.0/20",
                "ticketing.rate-limit.client-ip-header", "X-Real-Client")));
        Binder empty = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.rate-limit.trusted-proxies", "")));

        RateLimitProperties bound = binder.bindOrCreate("ticketing.rate-limit", RateLimitProperties.class);
        RateLimitProperties unset = empty.bindOrCreate("ticketing.rate-limit", RateLimitProperties.class);

        assertThat(bound.trustedProxies()).containsExactly("10.0.0.0/20", "10.0.16.0/20");
        assertThat(bound.clientIpHeader()).isEqualTo("X-Real-Client");
        assertThat(unset.trustedProxies()).isEmpty();
    }

    @Test
    void headRequestsShareThePublicLimitWithGet() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        MockServerHttpRequest get = MockServerHttpRequest.get("/api/v1/events")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 1234)).build();
        MockServerHttpRequest head = MockServerHttpRequest.head("/api/v1/events/x")
                .remoteAddress(new InetSocketAddress("10.0.0.2", 1234)).build();

        StepVerifier.create(filter.filter(MockServerWebExchange.from(get), counting(passed))).verifyComplete();
        MockServerWebExchange limited = MockServerWebExchange.from(head);
        StepVerifier.create(filter.filter(limited, counting(passed))).verifyComplete();

        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(passed).hasValue(1);
    }

    @Test
    void otherEndpointsAreNotLimited() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            StepVerifier.create(filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders/x")), counting(passed))).verifyComplete();
        }
        assertThat(passed).hasValue(5);
    }

    @Test
    void limitsConfirmationsPerAuthenticatedUserWithItsOwnBucket() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        ServerWebExchange limited = null;

        for (int i = 0; i < 3; i++) {
            limited = as("alice", MockServerHttpRequest.post("/api/v1/orders/o-" + i + "/confirm"));
            StepVerifier.create(filter.filter(limited, counting(passed))).verifyComplete();
        }
        StepVerifier.create(filter.filter(as("bob", MockServerHttpRequest.post("/api/v1/orders/o/confirm")),
                counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(as("alice", MockServerHttpRequest.post("/api/v1/orders")),
                counting(passed))).verifyComplete();

        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(passed).as("alice x2, bob and alice's purchase (other bucket)").hasValue(4);
    }

    @Test
    void adminOperationsShareOnePerUserBucket() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();
        ServerWebExchange update = as("admin-1", MockServerHttpRequest.put("/api/v1/events/e1"));
        ServerWebExchange complimentary = as("admin-1", MockServerHttpRequest.post("/api/v1/events/e1/complimentary"));
        ServerWebExchange create = as("admin-1", MockServerHttpRequest.post("/api/v1/events"));
        ServerWebExchange otherAdmin = as("admin-2", MockServerHttpRequest.put("/api/v1/events/e1"));

        StepVerifier.create(filter.filter(update, counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(complimentary, counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(create, counting(passed))).verifyComplete();
        StepVerifier.create(filter.filter(otherAdmin, counting(passed))).verifyComplete();

        assertThat(create.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(passed).hasValue(3);
    }

    @Test
    void anonymousMutationsFallBackToTheClientIp() {
        RateLimitFilter filter = filter();
        AtomicInteger passed = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            StepVerifier.create(filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.put("/api/v1/events/e1")), counting(passed))).verifyComplete();
        }
        assertThat(passed).hasValue(2);
    }

    private static MockServerHttpRequest.BaseBuilder<?> stream(String ip) {
        return MockServerHttpRequest.get("/api/v1/events/e1/availability/stream")
                .remoteAddress(new InetSocketAddress(ip, 1234));
    }

    private static Mono<Void> open(RateLimitFilter filter, ServerWebExchange exchange, Sinks.Empty<Void> stream) {
        return filter.filter(exchange, ignored -> stream.asMono());
    }

    @Test
    void capsConcurrentAvailabilityStreamsPerClientAndReleasesOnCancel() {
        RateLimitFilter filter = filter(UNLIMITED, new RateLimitProperties.StreamLimit(2, 10));
        Disposable first = open(filter, MockServerWebExchange.from(stream("198.51.100.1")), Sinks.empty()).subscribe();
        Disposable second = open(filter, MockServerWebExchange.from(stream("198.51.100.1")), Sinks.empty()).subscribe();
        MockServerWebExchange rejected = MockServerWebExchange.from(stream("198.51.100.1"));

        StepVerifier.create(open(filter, rejected, Sinks.empty())).verifyComplete();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo(Long.toString(RateLimitFilter.STREAM_RETRY_AFTER.toSeconds()));
        StepVerifier.create(rejected.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("\"code\":\"RATE_LIMITED\""))
                .verifyComplete();
        Disposable otherClient = open(filter, MockServerWebExchange.from(stream("198.51.100.2")), Sinks.empty())
                .subscribe();

        first.dispose();
        MockServerWebExchange afterCancel = MockServerWebExchange.from(stream("198.51.100.1"));
        Disposable third = open(filter, afterCancel, Sinks.empty()).subscribe();

        assertThat(afterCancel.getResponse().getStatusCode()).as("the cancelled stream released its slot").isNull();
        List.of(second, otherClient, third).forEach(Disposable::dispose);
    }

    @Test
    void capsConcurrentAvailabilityStreamsGloballyAndReleasesOnComplete() {
        RateLimitFilter filter = filter(UNLIMITED, new RateLimitProperties.StreamLimit(5, 2));
        Sinks.Empty<Void> finishing = Sinks.empty();
        AtomicInteger completed = new AtomicInteger();
        open(filter, MockServerWebExchange.from(stream("198.51.100.1")), finishing)
                .subscribe(null, null, completed::incrementAndGet);
        Disposable held = open(filter, MockServerWebExchange.from(stream("198.51.100.2")), Sinks.empty()).subscribe();
        MockServerWebExchange rejected = MockServerWebExchange.from(stream("198.51.100.3"));

        StepVerifier.create(open(filter, rejected, Sinks.empty())).verifyComplete();
        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        finishing.tryEmitEmpty();
        MockServerWebExchange afterComplete = MockServerWebExchange.from(stream("198.51.100.3"));
        Disposable reopened = open(filter, afterComplete, Sinks.empty()).subscribe();

        assertThat(completed).hasValue(1);
        assertThat(afterComplete.getResponse().getStatusCode()).isNull();
        List.of(held, reopened).forEach(Disposable::dispose);
    }

    @Test
    void authenticatedStreamsAreCappedPerUserAcrossIps() {
        RateLimitFilter filter = filter(UNLIMITED, new RateLimitProperties.StreamLimit(1, 10));
        Disposable held = open(filter, as("alice", stream("198.51.100.1")), Sinks.empty()).subscribe();
        ServerWebExchange rejected = as("alice", stream("198.51.100.9"));

        StepVerifier.create(open(filter, rejected, Sinks.empty())).verifyComplete();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        held.dispose();
    }

    @Test
    void streamsStillConsumeThePublicRateLimit() {
        RateLimitFilter filter = filter(new RateLimitProperties.Limit(50, 1), new RateLimitProperties.StreamLimit(5, 5));
        Disposable held = open(filter, MockServerWebExchange.from(stream("198.51.100.1")), Sinks.empty()).subscribe();
        MockServerWebExchange rejected = MockServerWebExchange.from(stream("198.51.100.1"));

        StepVerifier.create(open(filter, rejected, Sinks.empty())).verifyComplete();

        assertThat(rejected.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        held.dispose();
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
