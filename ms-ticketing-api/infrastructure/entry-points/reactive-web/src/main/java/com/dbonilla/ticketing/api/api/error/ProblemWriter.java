package com.dbonilla.ticketing.api.api.error;

import com.dbonilla.ticketing.api.resilience.TraceContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.HexFormat;
import java.util.UUID;

/**
 * Writes {@code application/problem+json} responses outside of the handlers (filters, security, the
 * global exception handler).
 */
@Component
public class ProblemWriter {

    static final String TRACEPARENT_HEADER = "traceparent";
    private static final int TRACE_ID_LENGTH = 32;
    private static final int TRACEPARENT_PARTS = 4;

    private final JsonMapper jsonMapper;

    public ProblemWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ProblemResponse problem) {
        ServerHttpResponse response = exchange.getResponse();
        ApiProblem body = new ApiProblem(problem.type().type().toString(), problem.type().title(),
                problem.httpStatus(), problem.type().name(), problem.detail(),
                exchange.getRequest().getPath().value(), traceId(exchange), problem.errors());
        response.setStatusCode(HttpStatus.valueOf(problem.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (problem.retryAfter() != null) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(problem.retryAfter().toSeconds()));
        }
        return Mono.fromCallable(() -> response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(body)))
                .flatMap(buffer -> response.writeWith(Mono.just(buffer)));
    }

    /** Current span, else the trace id of the incoming {@code traceparent}, else a fresh id. */
    static String traceId(ServerWebExchange exchange) {
        return TraceContext.currentTraceId().orElseGet(() -> incomingTraceId(exchange));
    }

    private static String incomingTraceId(ServerWebExchange exchange) {
        String traceparent = exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER);
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length == TRACEPARENT_PARTS && parts[1].length() == TRACE_ID_LENGTH
                    && parts[1].chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return parts[1];
            }
        }
        UUID random = UUID.randomUUID();
        return HexFormat.of().toHexDigits(random.getMostSignificantBits())
                + HexFormat.of().toHexDigits(random.getLeastSignificantBits());
    }
}
