package com.dbonilla.ticketing.expirer.bootstrap;

import com.dbonilla.ticketing.expirer.dynamodb.DynamoTables;
import com.dbonilla.ticketing.expirer.sqs.sender.QueueUrls;
import com.dbonilla.ticketing.expirer.usecase.shared.ScanSettings;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration read from environment variables (docs/design/conventions.md, section 5). Fails fast at
 * cold start with the names of the invalid variables (never their values).
 *
 * @param endpoint {@code AWS_ENDPOINT_URL}, only set locally (LocalStack); {@code null} in AWS
 */
public record ExpirerSettings(String region, URI endpoint, DynamoTables tables, QueueUrls queues,
                              Duration reservationTtl, Duration reconciliationDeadline, ScanSettings scan) {

    static final String AWS_REGION = "AWS_REGION";
    static final String AWS_ENDPOINT_URL = "AWS_ENDPOINT_URL";
    static final String EVENTS_TABLE = "EVENTS_TABLE";
    static final String ORDERS_TABLE = "ORDERS_TABLE";
    static final String AUDIT_TABLE = "AUDIT_TABLE";
    static final String ORDERS_QUEUE_URL = "ORDERS_QUEUE_URL";
    static final String CONFIRMATIONS_QUEUE_URL = "CONFIRMATIONS_QUEUE_URL";
    static final String RESERVATION_TTL_SECONDS = "RESERVATION_TTL_SECONDS";
    static final String RECONCILIATION_DEADLINE_SECONDS = "RECONCILIATION_DEADLINE_SECONDS";
    static final String PAGE_SIZE = "EXPIRER_PAGE_SIZE";
    static final String CONCURRENCY = "EXPIRER_CONCURRENCY";

    static final long DEFAULT_RESERVATION_TTL_SECONDS = 600;
    static final long DEFAULT_RECONCILIATION_DEADLINE_SECONDS = 300;
    static final long DEFAULT_PAGE_SIZE = 100;
    static final long DEFAULT_CONCURRENCY = 4;
    private static final long MAX_PAGE_SIZE = 1_000;
    private static final long MAX_CONCURRENCY = 32;

    public Optional<URI> endpointOverride() {
        return Optional.ofNullable(endpoint);
    }

    public static ExpirerSettings fromEnvironment(Map<String, String> env) {
        EnvironmentReader reader = new EnvironmentReader(env);
        String region = reader.required(AWS_REGION);
        URI endpoint = reader.optionalUrl(AWS_ENDPOINT_URL);
        String events = reader.required(EVENTS_TABLE);
        String orders = reader.required(ORDERS_TABLE);
        String audit = reader.required(AUDIT_TABLE);
        String ordersQueue = reader.required(ORDERS_QUEUE_URL);
        String confirmationsQueue = reader.required(CONFIRMATIONS_QUEUE_URL);
        long ttl = reader.positive(RESERVATION_TTL_SECONDS, DEFAULT_RESERVATION_TTL_SECONDS, Long.MAX_VALUE);
        long deadline = reader.positive(RECONCILIATION_DEADLINE_SECONDS, DEFAULT_RECONCILIATION_DEADLINE_SECONDS,
                Long.MAX_VALUE);
        long pageSize = reader.positive(PAGE_SIZE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        long concurrency = reader.positive(CONCURRENCY, DEFAULT_CONCURRENCY, MAX_CONCURRENCY);
        reader.failOnErrors();
        return new ExpirerSettings(region, endpoint, new DynamoTables(events, orders, audit),
                new QueueUrls(ordersQueue, confirmationsQueue), Duration.ofSeconds(ttl), Duration.ofSeconds(deadline),
                new ScanSettings((int) pageSize, (int) concurrency));
    }

    /** Collects every invalid variable so a misconfiguration is reported in one go. */
    private static final class EnvironmentReader {
        private final Map<String, String> env;
        private final List<String> errors = new ArrayList<>();

        private EnvironmentReader(Map<String, String> env) {
            this.env = Map.copyOf(env);
        }

        private Optional<String> value(String name) {
            return Optional.ofNullable(env.get(name)).map(String::trim).filter(value -> !value.isEmpty());
        }

        String required(String name) {
            return value(name).orElseGet(() -> {
                errors.add(name + " is required");
                return null;
            });
        }

        URI optionalUrl(String name) {
            return value(name).map(raw -> parseUrl(name, raw)).orElse(null);
        }

        private URI parseUrl(String name, String raw) {
            try {
                URI uri = URI.create(raw);
                if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                    return uri;
                }
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
            errors.add(name + " must be an absolute http(s) URL");
            return null;
        }

        long positive(String name, long defaultValue, long max) {
            return value(name).map(raw -> parsePositive(name, raw, defaultValue, max)).orElse(defaultValue);
        }

        private long parsePositive(String name, String raw, long defaultValue, long max) {
            try {
                long parsed = Long.parseLong(raw);
                if (parsed > 0 && parsed <= max) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // reported below
            }
            errors.add(name + " must be an integer between 1 and " + max);
            return defaultValue;
        }

        void failOnErrors() {
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Invalid configuration: " + String.join("; ", errors));
            }
        }
    }
}
