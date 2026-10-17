package com.dbonilla.ticketing.api.api.error;

import com.dbonilla.ticketing.api.model.exception.DomainValidationException;
import com.dbonilla.ticketing.api.model.exception.ServiceUnavailableException;
import com.dbonilla.ticketing.api.model.exception.TicketingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Duration;
import java.util.List;

/**
 * Translates any error into a problem. Only messages written by this service reach the client; internal
 * and third-party messages never do (OWASP A10).
 */
public final class ProblemMapper {

    public static final Duration SERVICE_UNAVAILABLE_RETRY_AFTER = Duration.ofSeconds(5);
    static final String INTERNAL_DETAIL = "An unexpected error occurred";
    static final String UNAVAILABLE_DETAIL = "A dependency is temporarily unavailable, retry later";
    static final String MALFORMED_DETAIL = "The request is malformed";
    static final String TOO_LARGE_DETAIL = "The request body exceeds the allowed size";

    private ProblemMapper() {
    }

    public static ProblemResponse map(Throwable error) {
        if (hasCause(error, DataBufferLimitException.class)) {
            return ProblemResponse.of(ProblemType.PAYLOAD_TOO_LARGE, TOO_LARGE_DETAIL);
        }
        return switch (error) {
            case TicketingException domain -> fromDomain(domain);
            case RequestValidationException invalid -> ProblemResponse
                    .of(ProblemType.VALIDATION_ERROR, invalid.getMessage()).withErrors(invalid.violations());
            case PreconditionRequiredException missing ->
                    ProblemResponse.of(ProblemType.PRECONDITION_REQUIRED, missing.getMessage());
            case AuthenticationException ignored -> unauthorized();
            case AccessDeniedException ignored -> forbidden();
            case ServerWebInputException ignored -> ProblemResponse.of(ProblemType.VALIDATION_ERROR, MALFORMED_DETAIL);
            case ResponseStatusException status -> fromStatus(status.getStatusCode().value());
            default -> ProblemResponse.of(ProblemType.INTERNAL_ERROR, INTERNAL_DETAIL);
        };
    }

    public static ProblemResponse unauthorized() {
        return ProblemResponse.of(ProblemType.UNAUTHORIZED, "A valid bearer token is required");
    }

    public static ProblemResponse forbidden() {
        return ProblemResponse.of(ProblemType.FORBIDDEN, "The token does not grant the required scope");
    }

    private static ProblemResponse fromDomain(TicketingException error) {
        return switch (error) {
            case DomainValidationException invalid -> ProblemResponse
                    .of(ProblemType.VALIDATION_ERROR, invalid.getMessage())
                    .withErrors(List.of(new FieldViolation(invalid.field(), invalid.getMessage())));
            case ServiceUnavailableException ignored -> ProblemResponse
                    .of(ProblemType.SERVICE_UNAVAILABLE, UNAVAILABLE_DETAIL)
                    .withRetryAfter(SERVICE_UNAVAILABLE_RETRY_AFTER);
            default -> ProblemResponse.of(ProblemType.valueOf(error.code().name()), error.getMessage());
        };
    }

    private static ProblemResponse fromStatus(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return unauthorized();
        }
        if (status == HttpStatus.FORBIDDEN.value()) {
            return forbidden();
        }
        if (status == HttpStatus.CONTENT_TOO_LARGE.value()) {
            return ProblemResponse.of(ProblemType.PAYLOAD_TOO_LARGE, TOO_LARGE_DETAIL);
        }
        if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return ProblemResponse.of(ProblemType.INTERNAL_ERROR, INTERNAL_DETAIL);
        }
        return ProblemResponse.of(ProblemType.VALIDATION_ERROR, HttpStatus.valueOf(status).getReasonPhrase())
                .withStatus(status);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }
}
