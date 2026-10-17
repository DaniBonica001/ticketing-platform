package com.dbonilla.ticketing.processor.sqs.listener.tracing;

import com.dbonilla.ticketing.processor.sqs.listener.TestMessages;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTracingTest {

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    private ObservationRegistry tracingRegistry() {
        io.opentelemetry.api.trace.Tracer otelTracer = tracerProvider.get("test");
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        OtelTracer tracer = new OtelTracer(otelTracer, currentTraceContext, event -> { },
                new OtelBaggageManager(currentTraceContext, List.of(), List.of()));
        OtelPropagator propagator = new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otelTracer);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
        return registry;
    }

    @Test
    void continuesTheTraceOfTheProducer() {
        MessageTracing tracing = new MessageTracing(tracingRegistry());

        StepVerifier.create(tracing.observe(TestMessages.message("{}"), "OrderPlaced", Mono::just))
                .expectNext(TestMessages.TRACE_ID)
                .verifyComplete();
    }

    @Test
    void startsANewTraceWhenTheMessageHasNoTraceparent() {
        MessageTracing tracing = new MessageTracing(tracingRegistry());
        Message withoutTrace = TestMessages.message("{}").toBuilder().messageAttributes(Map.of()).build();

        StepVerifier.create(tracing.observe(withoutTrace, "OrderPlaced", Mono::just))
                .assertNext(traceId -> assertThat(traceId).hasSize(32).isNotEqualTo(TestMessages.TRACE_ID))
                .verifyComplete();
    }

    @Test
    void fallsBackToTheTraceparentWhenTracingIsDisabled() {
        MessageTracing tracing = new MessageTracing(ObservationRegistry.NOOP);

        StepVerifier.create(tracing.observe(TestMessages.message("{}"), "OrderPlaced", Mono::just))
                .expectNext(TestMessages.TRACE_ID)
                .verifyComplete();
    }

    @Test
    void returnsNoTraceIdWhenNothingIsAvailable() {
        MessageTracing tracing = new MessageTracing(ObservationRegistry.NOOP);
        Message withoutTrace = TestMessages.message("{}").toBuilder().messageAttributes(Map.of()).build();

        StepVerifier.create(tracing.observe(withoutTrace, "OrderPlaced",
                        traceId -> Mono.just(String.valueOf(traceId))))
                .expectNext("null")
                .verifyComplete();
    }

    @Test
    void errorsArePropagated() {
        MessageTracing tracing = new MessageTracing(tracingRegistry());

        StepVerifier.create(tracing.observe(TestMessages.message("{}"), "OrderPlaced",
                        traceId -> Mono.error(new IllegalStateException("boom"))))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void ignoresMalformedTraceparent() {
        Message malformed = TestMessages.message("{}").toBuilder().messageAttributes(Map.of("traceparent",
                software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                        .dataType("String").stringValue("garbage").build())).build();

        assertThat(MessageTracing.traceIdFromAttribute(malformed)).isEmpty();
    }
}
