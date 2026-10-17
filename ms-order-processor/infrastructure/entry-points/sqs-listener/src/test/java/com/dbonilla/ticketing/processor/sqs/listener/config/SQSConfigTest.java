package com.dbonilla.ticketing.processor.sqs.listener.config;

import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.sqs.listener.helper.SQSListener;
import com.dbonilla.ticketing.processor.sqs.listener.tracing.MessageTracing;
import com.dbonilla.ticketing.processor.usecase.processpayment.ProcessPaymentUseCase;
import com.dbonilla.ticketing.processor.usecase.reserveorder.ReserveOrderUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.metrics.LoggingMetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SQSConfigTest {

    private final SQSConfig config = new SQSConfig();

    private static SQSProperties properties(String endpoint) {
        return new SQSProperties("us-east-1", endpoint, "http://q/orders", "http://q/confirmations",
                20, 0, 10, 1, 10);
    }

    @Test
    void buildsClientWithAndWithoutEndpoint() {
        try (SqsAsyncClient local = config.sqsAsyncClient(properties("http://localhost:4566"), LoggingMetricPublisher.create());
             SqsAsyncClient aws = config.sqsAsyncClient(properties(null), LoggingMetricPublisher.create());
             SqsAsyncClient blank = config.sqsAsyncClient(properties(" "), LoggingMetricPublisher.create())) {
            assertThat(local).isNotNull();
            assertThat(aws).isNotNull();
            assertThat(blank).isNotNull();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsOneListenerPerQueue() {
        // Arrange
        SqsAsyncClient client = mock(SqsAsyncClient.class);
        when(client.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(new CompletableFuture<>());
        ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        MessageTracing tracing = config.messageTracing(provider);
        BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());

        // Act
        SQSListener orders = config.ordersQueueListener(client, properties(null), mock(ReserveOrderUseCase.class),
                config.orderMessageValidator(), tracing, metrics);
        SQSListener confirmations = config.confirmationsQueueListener(client, properties(null),
                mock(ProcessPaymentUseCase.class), config.orderMessageValidator(), tracing, metrics);

        // Assert
        assertThat(orders.isRunning()).isTrue();
        assertThat(confirmations.isRunning()).isTrue();
        orders.stop();
        confirmations.stop();
        assertThat(SQSConfig.settings(properties(null), "http://q/orders").queueUrl()).isEqualTo("http://q/orders");
    }
}
