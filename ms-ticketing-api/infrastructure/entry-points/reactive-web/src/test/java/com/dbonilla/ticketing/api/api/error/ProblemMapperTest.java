package com.dbonilla.ticketing.api.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemMapperTest {

    @Test
    void mapsSecurityErrors() {
        assertThat(ProblemMapper.map(new BadCredentialsException("x")).type()).isEqualTo(ProblemType.UNAUTHORIZED);
        assertThat(ProblemMapper.map(new AccessDeniedException("x")).type()).isEqualTo(ProblemType.FORBIDDEN);
    }

    @Test
    void mapsBufferLimitAnywhereInTheCauseChain() {
        ServerWebInputException wrapped = new ServerWebInputException("bad", null, new DataBufferLimitException("big"));

        assertThat(ProblemMapper.map(wrapped).type()).isEqualTo(ProblemType.PAYLOAD_TOO_LARGE);
        assertThat(ProblemMapper.map(new ServerWebInputException("bad")).type()).isEqualTo(ProblemType.VALIDATION_ERROR);
    }

    @Test
    void mapsFrameworkStatusErrors() {
        assertThat(ProblemMapper.map(new ResponseStatusException(HttpStatus.UNAUTHORIZED)).type())
                .isEqualTo(ProblemType.UNAUTHORIZED);
        assertThat(ProblemMapper.map(new ResponseStatusException(HttpStatus.FORBIDDEN)).type())
                .isEqualTo(ProblemType.FORBIDDEN);
        assertThat(ProblemMapper.map(new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE)).type())
                .isEqualTo(ProblemType.PAYLOAD_TOO_LARGE);
        assertThat(ProblemMapper.map(new ResponseStatusException(HttpStatus.BAD_GATEWAY)).type())
                .isEqualTo(ProblemType.INTERNAL_ERROR);
        ProblemResponse unsupported = ProblemMapper.map(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThat(unsupported.type()).isEqualTo(ProblemType.VALIDATION_ERROR);
        assertThat(unsupported.httpStatus()).isEqualTo(415);
    }

    @Test
    void typeUrisAreKebabCase() {
        assertThat(ProblemType.IDEMPOTENCY_KEY_REUSED.type().toString())
                .isEqualTo("https://ticketing.local/problems/idempotency-key-reused");
        assertThat(ProblemType.RATE_LIMITED.status()).isEqualTo(429);
    }

    @Test
    void writerUsesIncomingTraceparentWhenThereIsNoSpan() {
        ProblemWriter writer = new ProblemWriter(JsonMapper.builder().build());
        MockServerWebExchange withTrace = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        MockServerWebExchange malformed = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header("traceparent", "00-zz-00f067aa0ba902b7-01"));
        MockServerWebExchange none = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        assertThat(ProblemWriter.traceId(withTrace)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ProblemWriter.traceId(malformed)).hasSize(32).isNotEqualTo("zz");
        assertThat(ProblemWriter.traceId(none)).hasSize(32);
        StepVerifier.create(writer.write(none, ProblemMapper.unauthorized())).verifyComplete();
        assertThat(none.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void globalHandlerDoesNotRewriteCommittedResponses() {
        GlobalErrorHandler handler = new GlobalErrorHandler(new ProblemWriter(JsonMapper.builder().build()));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        IllegalStateException error = new IllegalStateException("late");

        StepVerifier.create(exchange.getResponse().setComplete().then(handler.handle(exchange, error)))
                .verifyErrorMatches(thrown -> thrown == error);
    }
}
