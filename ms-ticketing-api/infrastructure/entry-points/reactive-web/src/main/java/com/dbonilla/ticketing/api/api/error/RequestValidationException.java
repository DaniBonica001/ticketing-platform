package com.dbonilla.ticketing.api.api.error;

import java.util.List;

/**
 * Invalid payload, header or parameter (400 VALIDATION_ERROR).
 */
public class RequestValidationException extends RuntimeException {

    private final List<FieldViolation> violations;

    public RequestValidationException(List<FieldViolation> violations) {
        super("Request validation failed");
        this.violations = List.copyOf(violations);
    }

    public static RequestValidationException of(String field, String message) {
        return new RequestValidationException(List.of(new FieldViolation(field, message)));
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
