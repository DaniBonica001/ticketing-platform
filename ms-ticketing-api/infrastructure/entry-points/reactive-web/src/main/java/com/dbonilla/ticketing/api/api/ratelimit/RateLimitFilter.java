package com.dbonilla.ticketing.api.api.ratelimit;

import com.dbonilla.ticketing.api.api.error.ProblemResponse;
import com.dbonilla.ticketing.api.api.error.ProblemType;
import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs after Spring Security (order -100), so the user of the authenticated routes is already known.
 * <ul>
 *   <li>Per user ({@code sub}): POST /orders, POST /orders/{id}/confirm and the admin operations, each with its
 *   own token bucket.</li>
 *   <li>Per client IP: public reads.</li>
 *   <li>Availability streams: additionally capped by concurrent streams per client (user, else IP) and per
 *   instance; the slot is released when the stream ends, fails or is cancelled.</li>
 * </ul>
 */
@Component
@Order(0)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements WebFilter {

    static final String ORDERS_PATH = "/api/v1/orders";
    static final String PUBLIC_PREFIX = "/api/v1/events";
    /** No precise wait is known for a stream slot: a fixed, conservative hint. */
    static final Duration STREAM_RETRY_AFTER = Duration.ofSeconds(5);
    private static final PathPattern CONFIRM = pattern("/api/v1/orders/{orderId}/confirm");
    private static final PathPattern EVENT = pattern("/api/v1/events/{eventId}");
    private static final PathPattern COMPLIMENTARY = pattern("/api/v1/events/{eventId}/complimentary");
    private static final PathPattern AVAILABILITY_STREAM = pattern("/api/v1/events/{eventId}/availability/stream");
    private static final long MIN_RETRY_AFTER_SECONDS = 1;
    private static final String USER_KEY = "user:";
    private static final String IP_KEY = "ip:";

    private final RateLimiter ordersLimiter;
    private final RateLimiter confirmLimiter;
    private final RateLimiter adminLimiter;
    private final RateLimiter publicLimiter;
    private final ConcurrencyLimiter streams;
    private final ProblemWriter problems;
    private final ClientIpResolver clientIps;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties, ProblemWriter problems) {
        this(properties, problems, System::nanoTime);
    }

    RateLimitFilter(RateLimitProperties properties, ProblemWriter problems, LongSupplier clock) {
        this.ordersLimiter = limiter(properties.orders(), properties, clock);
        this.confirmLimiter = limiter(properties.confirm(), properties, clock);
        this.adminLimiter = limiter(properties.adminOperations(), properties, clock);
        this.publicLimiter = limiter(properties.publicEndpoints(), properties, clock);
        this.streams = new ConcurrencyLimiter(properties.availabilityStreams());
        this.problems = problems;
        this.clientIps = new ClientIpResolver(properties.trustedProxies(), properties.clientIpHeader());
    }

    private static RateLimiter limiter(RateLimitProperties.Limit limit, RateLimitProperties properties,
                                       LongSupplier clock) {
        return new RateLimiter(limit, properties.maxKeys(), properties.idleTimeout(), clock);
    }

    private static PathPattern pattern(String path) {
        return PathPatternParser.defaultInstance.parse(path);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        PathContainer path = request.getPath().pathWithinApplication();
        if (HttpMethod.POST.equals(method) && ORDERS_PATH.equals(path.value())) {
            return perClient(ordersLimiter, exchange, chain);
        }
        if (HttpMethod.POST.equals(method) && CONFIRM.matches(path)) {
            return perClient(confirmLimiter, exchange, chain);
        }
        if (isAdminOperation(method, path)) {
            return perClient(adminLimiter, exchange, chain);
        }
        if (isReadMethod(method) && path.value().startsWith(PUBLIC_PREFIX)) {
            boolean isStream = HttpMethod.GET.equals(method) && AVAILABILITY_STREAM.matches(path);
            return throttle(publicLimiter, IP_KEY + clientIps.resolve(request), exchange,
                    () -> isStream ? openStream(exchange, chain) : chain.filter(exchange));
        }
        return chain.filter(exchange);
    }

    private static boolean isAdminOperation(HttpMethod method, PathContainer path) {
        if (HttpMethod.PUT.equals(method)) {
            return EVENT.matches(path);
        }
        return HttpMethod.POST.equals(method) && (PUBLIC_PREFIX.equals(path.value()) || COMPLIMENTARY.matches(path));
    }

    private static boolean isReadMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }

    /** Authenticated subject; the client IP only when there is no principal. */
    private Mono<String> clientKey(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(principal -> USER_KEY + principal.getName())
                .defaultIfEmpty(IP_KEY + clientIps.resolve(exchange.getRequest()));
    }

    private Mono<Void> perClient(RateLimiter limiter, ServerWebExchange exchange, WebFilterChain chain) {
        return clientKey(exchange).flatMap(key -> throttle(limiter, key, exchange, () -> chain.filter(exchange)));
    }

    private Mono<Void> throttle(RateLimiter limiter, String key, ServerWebExchange exchange,
                                Supplier<Mono<Void>> next) {
        RateLimitDecision decision = limiter.tryAcquire(key);
        if (decision.isAllowed()) {
            return next.get();
        }
        return reject(exchange, "Rate limit exceeded, retry later", retryAfter(decision.retryAfter()));
    }

    /** The slot is held for the whole response, which for SSE lasts until the stream ends or the client leaves. */
    private Mono<Void> openStream(ServerWebExchange exchange, WebFilterChain chain) {
        return clientKey(exchange).flatMap(key -> streams.tryAcquire(key)
                .map(permit -> Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> permit.release()))
                .orElseGet(() -> reject(exchange, "Too many concurrent availability streams, retry later",
                        STREAM_RETRY_AFTER)));
    }

    private Mono<Void> reject(ServerWebExchange exchange, String detail, Duration retryAfter) {
        ProblemResponse problem = ProblemResponse.of(ProblemType.RATE_LIMITED, detail).withRetryAfter(retryAfter);
        return problems.write(exchange, problem);
    }

    static Duration retryAfter(Duration wait) {
        long seconds = (wait.toMillis() + 999) / 1000;
        return Duration.ofSeconds(Math.max(MIN_RETRY_AFTER_SECONDS, seconds));
    }
}
