package com.dbonilla.ticketing.api.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * RFC 9457 Problem Details body with the {@code code}, {@code traceId} and {@code errors} extensions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String type, String title, int status, String code, String detail, String instance,
                         String traceId, List<FieldViolation> errors) {

    public ApiProblem {
        errors = errors == null || errors.isEmpty() ? null : List.copyOf(errors);
    }
}
