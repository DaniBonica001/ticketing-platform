package com.dbonilla.ticketing.api.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Reactive global handler: every error leaves as Problem Details. 5xx are logged with their cause; the
 * client only receives the generic problem.
 */
@Component
@Order(-2)
public class GlobalErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);
    private static final int SERVER_ERROR = 500;

    private final ProblemWriter writer;

    public GlobalErrorHandler(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        ProblemResponse problem = ProblemMapper.map(error);
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();
        if (problem.httpStatus() >= SERVER_ERROR) {
            log.error("Request {} {} failed with {}", method, path, problem.type(), error);
        } else {
            log.info("Request {} {} rejected with {}", method, path, problem.type());
        }
        return writer.write(exchange, problem);
    }
}
