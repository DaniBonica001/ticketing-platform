package com.dbonilla.ticketing.expirer.otel;

import com.dbonilla.ticketing.expirer.model.metrics.RunMetrics;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelRunMetricsPublisherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
    private final SdkMeterProvider meterProvider = provider(exporter);
    private final OtelRunMetricsPublisher publisher = new OtelRunMetricsPublisher(meterProvider, TIMEOUT);

    private static SdkMeterProvider provider(MetricExporter exporter) {
        return SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofHours(1)).build())
                .build();
    }

    private Map<String, Long> exportedSums() {
        List<MetricData> metrics = exporter.getFinishedMetricItems();
        return metrics.stream()
                .flatMap(metric -> metric.getLongSumData().getPoints().stream()
                        .map(point -> Map.entry(key(metric.getName(), point), point.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, last) -> last));
    }

    private static String key(String name, LongPointData point) {
        String status = point.getAttributes().get(OtelRunMetricsPublisher.STATUS);
        return status == null ? name : name + "{" + status + "}";
    }

    @Test
    void exportsEveryCounterOnPublish() {
        publisher.publish(new RunMetrics(3, 2, 1, 4));

        assertThat(exportedSums()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ticketing.reservations.expired", 3L,
                "ticketing.orders.republished{QUEUED}", 2L,
                "ticketing.orders.republished{PENDING_CONFIRMATION}", 1L,
                "ticketing.expirer.failures", 4L));
        assertThat(exporter.getFinishedMetricItems())
                .allSatisfy(metric -> assertThat(metric.getLongSumData().getAggregationTemporality())
                        .isEqualTo(AggregationTemporality.CUMULATIVE));
    }

    @Test
    void exportsZeroValuedSeriesOnAnEmptyRun() {
        publisher.publish(new RunMetrics(0, 0, 0, 0));

        assertThat(exportedSums()).hasSize(4).allSatisfy((name, value) -> assertThat(value).isZero());
    }

    @Test
    void countersAccumulateAcrossInvocations() {
        publisher.publish(new RunMetrics(1, 0, 0, 0));
        exporter.reset();
        publisher.publish(new RunMetrics(2, 0, 0, 0));

        assertThat(exportedSums()).containsEntry("ticketing.reservations.expired", 3L);
    }

    @Test
    void neverThrowsWhenTheExportFails() {
        OtelRunMetricsPublisher failing = new OtelRunMetricsPublisher(provider(new FailingExporter()), TIMEOUT);

        assertThatCode(() -> failing.publish(new RunMetrics(1, 0, 0, 0))).doesNotThrowAnyException();
    }

    @Test
    void neverThrowsWhenTheExporterThrows() {
        OtelRunMetricsPublisher throwing = new OtelRunMetricsPublisher(provider(new ThrowingExporter()), TIMEOUT);

        assertThatCode(() -> throwing.publish(new RunMetrics(1, 0, 0, 0))).doesNotThrowAnyException();
    }

    @Test
    void neverThrowsAfterTheProviderIsShutDown() {
        meterProvider.shutdown().join(1, java.util.concurrent.TimeUnit.SECONDS);

        assertThatCode(() -> publisher.publish(new RunMetrics(1, 0, 0, 0))).doesNotThrowAnyException();
    }

    @Test
    void requiresItsCollaborators() {
        assertThatThrownBy(() -> new OtelRunMetricsPublisher(null, TIMEOUT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OtelRunMetricsPublisher(meterProvider, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void attributesAreOnlyTheStatus() {
        publisher.publish(new RunMetrics(0, 1, 0, 0));

        assertThat(exporter.getFinishedMetricItems())
                .filteredOn(metric -> metric.getName().equals("ticketing.orders.republished"))
                .flatMap(metric -> metric.getLongSumData().getPoints())
                .extracting(LongPointData::getAttributes)
                .containsExactlyInAnyOrder(Attributes.of(OtelRunMetricsPublisher.STATUS, "QUEUED"),
                        Attributes.of(OtelRunMetricsPublisher.STATUS, "PENDING_CONFIRMATION"));
    }

    private static class FailingExporter implements MetricExporter {
        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            return CompletableResultCode.ofFailure();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public MemoryMode getMemoryMode() {
            return MemoryMode.IMMUTABLE_DATA;
        }
    }

    private static final class ThrowingExporter extends FailingExporter {
        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            throw new IllegalStateException("collector down");
        }
    }
}
