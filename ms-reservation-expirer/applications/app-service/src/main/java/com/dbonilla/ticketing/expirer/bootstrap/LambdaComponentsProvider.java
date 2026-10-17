package com.dbonilla.ticketing.expirer.bootstrap;

import com.dbonilla.ticketing.expirer.dynamodb.DynamoDbOrderRepository;
import com.dbonilla.ticketing.expirer.helpers.retry.RetrySettings;
import com.dbonilla.ticketing.expirer.helpers.retry.TransientRetry;
import com.dbonilla.ticketing.expirer.lambda.ExpirerComponents;
import com.dbonilla.ticketing.expirer.lambda.ExpirerComponentsProvider;
import com.dbonilla.ticketing.expirer.model.message.gateways.OrderMessagePublisher;
import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import com.dbonilla.ticketing.expirer.model.order.gateways.OrderRepository;
import com.dbonilla.ticketing.expirer.otel.OtelTelemetry;
import com.dbonilla.ticketing.expirer.otel.TelemetrySettings;
import com.dbonilla.ticketing.expirer.sqs.sender.SqsOrderMessagePublisher;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpireReservationsUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconcileStuckOrdersUseCase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Clock;
import java.util.Map;

/**
 * Composition root: manual constructor wiring instead of a Spring context, to keep the Lambda cold start
 * low. Registered in META-INF/services and discovered by the handler.
 */
public class LambdaComponentsProvider implements ExpirerComponentsProvider {

    private static final Logger LOG = LogManager.getLogger(LambdaComponentsProvider.class);

    @Override
    public ExpirerComponents create() {
        return create(System.getenv());
    }

    ExpirerComponents create(Map<String, String> environment) {
        ExpirerSettings settings = ExpirerSettings.fromEnvironment(environment);
        TelemetrySettings telemetry = TelemetrySettings.fromEnvironment(environment);
        AwsClientFactory clients = new AwsClientFactory(settings);
        LOG.info("Telemetry configured: enabled={} serviceName={}", telemetry.isEnabled(), telemetry.serviceName());
        return wire(settings, clients.dynamoDb(), clients.sqs(), Clock.systemUTC(), RetrySettings.DEFAULT,
                OtelTelemetry.metricsPublisher(telemetry));
    }

    static ExpirerComponents wire(ExpirerSettings settings, DynamoDbAsyncClient dynamoDb, SqsAsyncClient sqs,
                                  Clock clock, RetrySettings retrySettings, RunMetricsPublisher metrics) {
        Retry retry = TransientRetry.backoff(retrySettings);
        OrderRepository repository = new DynamoDbOrderRepository(dynamoDb, settings.tables(), retry);
        OrderMessagePublisher publisher = new SqsOrderMessagePublisher(sqs, settings.queues(), retry);
        LOG.info("Expirer configured: ordersTable={} pageSize={} concurrency={} reservationTtlSeconds={} "
                        + "reconciliationDeadlineSeconds={} localEndpoint={}",
                settings.tables().orders(), settings.scan().pageSize(), settings.scan().concurrency(),
                settings.reservationTtl().toSeconds(), settings.reconciliationDeadline().toSeconds(),
                settings.endpointOverride().isPresent());
        return new ExpirerComponents(
                new ExpireReservationsUseCase(repository, settings.scan()),
                new ReconcileStuckOrdersUseCase(repository, publisher, settings.scan(),
                        settings.reconciliationDeadline()),
                clock, metrics);
    }
}
