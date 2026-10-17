package com.dbonilla.ticketing.processor.paymentgateway;

import co.com.bancolombia.secretsmanager.api.GenericManagerAsync;
import co.com.bancolombia.secretsmanager.api.exceptions.SecretException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Reads the API key of the payment gateway from Secrets Manager (JSON {@code {"apiKey": "..."}}).
 * The key is loaded at startup and cached; failures are not cached, so a later call retries the read.
 * The secret value is never logged.
 */
public class PaymentGatewayCredentials implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayCredentials.class);
    private static final String API_KEY_FIELD = "apiKey";

    private final String secretId;
    private final Mono<ApiKey> cachedApiKey;

    public PaymentGatewayCredentials(GenericManagerAsync secretsManager, String secretId,
                                     JsonMapper jsonMapper, Duration cacheTtl) {
        this.secretId = secretId;
        this.cachedApiKey = Mono.defer(() -> readSecret(secretsManager, secretId))
                .map(secret -> parse(jsonMapper, secret))
                .cache(apiKey -> cacheTtl, error -> Duration.ZERO, () -> Duration.ZERO);
    }

    public Mono<ApiKey> apiKey() {
        return cachedApiKey;
    }

    @Override
    public void afterSingletonsInstantiated() {
        apiKey().subscribe(
                apiKey -> log.info("Payment gateway credentials loaded from secret {}", secretId),
                error -> log.warn("Payment gateway credentials could not be loaded from secret {}: {}",
                        secretId, error.getClass().getSimpleName()));
    }

    private static Mono<String> readSecret(GenericManagerAsync secretsManager, String secretId) {
        try {
            return secretsManager.getSecret(secretId);
        } catch (SecretException unreadable) {
            return Mono.error(unreadable);
        }
    }

    private static ApiKey parse(JsonMapper jsonMapper, String secret) {
        JsonNode apiKey = readJson(jsonMapper, secret).get(API_KEY_FIELD);
        if (apiKey == null || !apiKey.isString() || apiKey.asString().isBlank()) {
            throw new IllegalStateException("The payment gateway secret has no '" + API_KEY_FIELD + "' field");
        }
        return new ApiKey(apiKey.asString());
    }

    /**
     * Parsing errors are replaced (without cause) because their message may quote part of the secret.
     */
    private static JsonNode readJson(JsonMapper jsonMapper, String secret) {
        try {
            return jsonMapper.readTree(secret);
        } catch (JacksonException invalidJson) {
            throw new IllegalStateException("The payment gateway secret is not a JSON object");
        }
    }
}
