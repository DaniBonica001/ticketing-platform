package com.dbonilla.ticketing.processor.sqs.listener.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continues the W3C trace received in the {@code traceparent} message attribute: every message is processed inside
 * a CONSUMER observation whose parent is the producer span, so API -> queue -> processor is a single trace.
 */
public class MessageTracing {

    public static final String TRACEPARENT = "traceparent";
    static final String OBSERVATION_NAME = "ticketing.message.process";

    private static final Pattern TRACEPARENT_FORMAT =
            Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final ObservationRegistry registry;

    public MessageTracing(ObservationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Runs {@code work} inside the observation; {@code work} receives the trace id to correlate (may be null).
     */
    public <T> Mono<T> observe(Message message, String messageType, Function<String, Mono<T>> work) {
        return Mono.defer(() -> {
            Observation observation = start(message, messageType);
            String traceId = traceIdOf(observation).orElseGet(() -> traceIdFromAttribute(message).orElse(null));
            return work.apply(traceId)
                    .doOnError(observation::error)
                    .doFinally(signal -> observation.stop())
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    private Observation start(Message message, String messageType) {
        ReceiverContext<Message> context = new ReceiverContext<>(MessageTracing::attribute);
        context.setCarrier(message);
        context.setRemoteServiceName("aws-sqs");
        return Observation.createNotStarted(OBSERVATION_NAME, () -> context, registry)
                .contextualName(messageType + " process")
                .lowCardinalityKeyValue("messaging.system", "aws_sqs")
                .lowCardinalityKeyValue("messaging.message.type", messageType)
                .highCardinalityKeyValue("messaging.message.id", String.valueOf(message.messageId()))
                .start();
    }

    static String attribute(Message carrier, String key) {
        return Optional.ofNullable(carrier.messageAttributes().get(key))
                .map(MessageAttributeValue::stringValue)
                .orElse(null);
    }

    static Optional<String> traceIdFromAttribute(Message message) {
        return Optional.ofNullable(attribute(message, TRACEPARENT))
                .map(TRACEPARENT_FORMAT::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1));
    }

    private static Optional<String> traceIdOf(Observation observation) {
        TracingObservationHandler.TracingContext tracingContext =
                observation.getContextView().get(TracingObservationHandler.TracingContext.class);
        return Optional.ofNullable(tracingContext)
                .map(TracingObservationHandler.TracingContext::getSpan)
                .map(Span::context)
                .map(TraceContext::traceId)
                .filter(traceId -> !traceId.isBlank());
    }
}
