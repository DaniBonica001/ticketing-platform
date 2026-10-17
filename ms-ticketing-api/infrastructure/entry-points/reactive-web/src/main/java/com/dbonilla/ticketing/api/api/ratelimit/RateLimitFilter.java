package com.dbonilla.ticketing.api.api.ratelimit;

import com.dbonilla.ticketing.api.api.error.ProblemResponse;
import com.dbonilla.ticketing.api.api.error.ProblemType;
import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Runs after Spring Security (order -100), so the user of POST /orders is already authenticated.
 */
@Component
@Order(0)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements WebFilter {

    static final String ORDERS_PATH = "/api/v1/orders";
    static final String PUBLIC_PREFIX = "/api/v1/events";
    private static final String UNKNOWN_CLIENT = "unknown";
    private static final long MIN_RETRY_AFTER_SECONDS = 1;

    private final RateLimiter ordersLimiter;
    private final RateLimiter publicLimiter;
    private final ProblemWriter problems;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties, ProblemWriter problems) {
        this(properties, problems, System::nanoTime);
    }

    RateLimitFilter(RateLimitProperties properties, ProblemWriter problems, LongSupplier clock) {
        this.ordersLimiter = new RateLimiter(properties.orders(), properties.maxKeys(), properties.idleTimeout(), clock);
        this.publicLimiter = new RateLimiter(properties.publicEndpoints(), properties.maxKeys(),
                properties.idleTimeout(), clock);
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (HttpMethod.POST.equals(request.getMethod()) && ORDERS_PATH.equals(path)) {
            return exchange.getPrincipal()
                    .map(Principal::getName)
                    .defaultIfEmpty(clientIp(request))
                    .flatMap(user -> apply(ordersLimiter, "user:" + user, exchange, chain));
        }
        if (isReadMethod(request.getMethod()) && path.startsWith(PUBLIC_PREFIX)) {
            return apply(publicLimiter, "ip:" + clientIp(request), exchange, chain);
        }
        return chain.filter(exchange);
    }

    private static boolean isReadMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }

    private Mono<Void> apply(RateLimiter limiter, String key, ServerWebExchange exchange, WebFilterChain chain) {
        RateLimitDecision decision = limiter.tryAcquire(key);
        if (decision.isAllowed()) {
            return chain.filter(exchange);
        }
        ProblemResponse problem = ProblemResponse.of(ProblemType.RATE_LIMITED, "Rate limit exceeded, retry later")
                .withRetryAfter(retryAfter(decision.retryAfter()));
        return problems.write(exchange, problem);
    }

    static Duration retryAfter(Duration wait) {
        long seconds = (wait.toMillis() + 999) / 1000;
        return Duration.ofSeconds(Math.max(MIN_RETRY_AFTER_SECONDS, seconds));
    }

    /** Remote address of the connection; forwarded headers are not trusted (they can be spoofed). */
    private static String clientIp(ServerHttpRequest request) {
        return Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse(UNKNOWN_CLIENT);
    }
}
