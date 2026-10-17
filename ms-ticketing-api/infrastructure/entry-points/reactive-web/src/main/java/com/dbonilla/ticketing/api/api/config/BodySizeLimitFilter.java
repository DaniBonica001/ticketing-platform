package com.dbonilla.ticketing.api.api.config;

import com.dbonilla.ticketing.api.api.error.ProblemResponse;
import com.dbonilla.ticketing.api.api.error.ProblemType;
import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Rejects declared bodies above 16 KB before anything else runs. Bodies without Content-Length are
 * bounded by the codec limit ({@link WebFluxCodecConfig}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodySizeLimitFilter implements WebFilter {

    public static final int MAX_BODY_BYTES = 16 * 1024;

    private final ProblemWriter problems;

    public BodySizeLimitFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getHeaders().getContentLength() > MAX_BODY_BYTES) {
            return problems.write(exchange, ProblemResponse.of(ProblemType.PAYLOAD_TOO_LARGE,
                    "The request body exceeds " + MAX_BODY_BYTES + " bytes"));
        }
        return chain.filter(exchange);
    }
}
