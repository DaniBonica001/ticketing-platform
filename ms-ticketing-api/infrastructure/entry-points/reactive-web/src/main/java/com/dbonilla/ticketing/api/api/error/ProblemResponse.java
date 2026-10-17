package com.dbonilla.ticketing.api.api.error;

import java.time.Duration;
import java.util.List;

/**
 * A problem to render plus the optional {@code Retry-After} delay.
 */
public record ProblemResponse(ProblemType type, String detail, List<FieldViolation> errors, Integer status,
                              Duration retryAfter) {

    public ProblemResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ProblemResponse of(ProblemType type, String detail) {
        return new ProblemResponse(type, detail, List.of(), null, null);
    }

    public ProblemResponse withErrors(List<FieldViolation> violations) {
        return new ProblemResponse(type, detail, violations, status, retryAfter);
    }

    public ProblemResponse withStatus(int httpStatus) {
        return new ProblemResponse(type, detail, errors, httpStatus, retryAfter);
    }

    public ProblemResponse withRetryAfter(Duration delay) {
        return new ProblemResponse(type, detail, errors, status, delay);
    }

    public int httpStatus() {
        return status == null ? type.status() : status;
    }
}
