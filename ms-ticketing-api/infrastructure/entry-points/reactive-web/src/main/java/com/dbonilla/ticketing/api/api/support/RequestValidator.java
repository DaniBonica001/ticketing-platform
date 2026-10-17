package com.dbonilla.ticketing.api.api.support;

import com.dbonilla.ticketing.api.api.error.FieldViolation;
import com.dbonilla.ticketing.api.api.error.RequestValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Reads a JSON body and applies Bean Validation to it.
 */
@Component
public class RequestValidator {

    private final Validator validator;

    public RequestValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .switchIfEmpty(Mono.error(() -> RequestValidationException.of("body", "a request body is required")))
                .flatMap(this::validate);
    }

    public <T> Mono<T> validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (violations.isEmpty()) {
            return Mono.just(value);
        }
        List<FieldViolation> fields = violations.stream()
                .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return Mono.error(new RequestValidationException(fields));
    }
}
