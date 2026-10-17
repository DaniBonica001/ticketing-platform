package com.dbonilla.ticketing.processor.sqs.listener.config;

import com.dbonilla.ticketing.processor.metrics.BusinessMetrics;
import com.dbonilla.ticketing.processor.sqs.listener.OrderMessageProcessor;
import com.dbonilla.ticketing.processor.sqs.listener.helper.SQSListener;
import com.dbonilla.ticketing.processor.sqs.listener.message.MessageType;
import com.dbonilla.ticketing.processor.sqs.listener.message.OrderMessageValidator;
import com.dbonilla.ticketing.processor.sqs.listener.tracing.MessageTracing;
import com.dbonilla.ticketing.processor.usecase.processpayment.ProcessPaymentUseCase;
import com.dbonilla.ticketing.processor.usecase.reserveorder.ReserveOrderUseCase;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;

@Configuration
public class SQSConfig {

    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_ERROR_BACKOFF = Duration.ofSeconds(30);

    @Bean
    public SqsAsyncClient sqsAsyncClient(SQSProperties properties, MetricPublisher publisher) {
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(o -> o.addMetricPublisher(publisher));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        return builder.build();
    }

    @Bean
    public MessageTracing messageTracing(ObjectProvider<ObservationRegistry> registry) {
        return new MessageTracing(registry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    public OrderMessageValidator orderMessageValidator() {
        return new OrderMessageValidator(JsonMapper.builder().build());
    }

    @Bean(destroyMethod = "stop")
    public SQSListener ordersQueueListener(SqsAsyncClient client, SQSProperties properties,
                                           ReserveOrderUseCase reserveOrderUseCase, OrderMessageValidator validator,
                                           MessageTracing tracing, BusinessMetrics metrics) {
        OrderMessageProcessor processor = new OrderMessageProcessor(MessageType.ORDER_PLACED,
                reserveOrderUseCase::execute, validator, tracing, metrics);
        return new SQSListener(client, settings(properties, properties.ordersQueueUrl()), processor).start();
    }

    @Bean(destroyMethod = "stop")
    public SQSListener confirmationsQueueListener(SqsAsyncClient client, SQSProperties properties,
                                                  ProcessPaymentUseCase processPaymentUseCase,
                                                  OrderMessageValidator validator, MessageTracing tracing,
                                                  BusinessMetrics metrics) {
        OrderMessageProcessor processor = new OrderMessageProcessor(MessageType.ORDER_CONFIRMATION_REQUESTED,
                processPaymentUseCase::execute, validator, tracing, metrics);
        return new SQSListener(client, settings(properties, properties.confirmationsQueueUrl()), processor).start();
    }

    static SQSListener.ListenerSettings settings(SQSProperties properties, String queueUrl) {
        return new SQSListener.ListenerSettings(queueUrl, properties.waitTimeSeconds(),
                properties.maxNumberOfMessages(), properties.visibilityTimeoutSeconds(), properties.numberOfThreads(),
                properties.concurrency(), ERROR_BACKOFF, MAX_ERROR_BACKOFF);
    }
}
