package com.dbonilla.ticketing.expirer.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.dbonilla.ticketing.expirer.model.execution.ExecutionContext;
import com.dbonilla.ticketing.expirer.model.execution.OrderFailure;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpirationResult;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpireReservationsUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconcileStuckOrdersUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconciliationResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpirerHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-16T10:12:00.123456Z");
    private static final Map<String, Object> SCHEDULED_EVENT =
            Map.of("id", "e-1", "detail-type", "Scheduled Event", "source", "aws.events");

    private final ExpireReservationsUseCase expire = mock(ExpireReservationsUseCase.class);
    private final ReconcileStuckOrdersUseCase reconcile = mock(ReconcileStuckOrdersUseCase.class);
    private final Context context = mock(Context.class);
    private final ExpirerHandler handler = new ExpirerHandler(
            new ExpirerComponents(expire, reconcile, Clock.fixed(NOW, ZoneOffset.UTC)));

    @Test
    void returnsTheSummaryOfBothUseCases() {
        when(context.getAwsRequestId()).thenReturn("req-1");
        when(expire.execute(any())).thenReturn(Mono.just(new ExpirationResult(3, 1, List.of())));
        when(reconcile.execute(any())).thenReturn(Mono.just(new ReconciliationResult(2, 1, List.of())));

        ExpirationSummary summary = handler.handleRequest(SCHEDULED_EVENT, context);

        assertThat(summary).isEqualTo(new ExpirationSummary(3, 1, 2, 1, 0));
    }

    @Test
    void countsAndReportsFailuresWithoutFailingTheInvocation() {
        OrderFailure expireFailure = OrderFailure.of("expire", OrderStatus.RESERVED, "o1",
                new IllegalStateException("boom"));
        OrderFailure queryFailure = new OrderFailure("query", OrderStatus.QUEUED, null, "x.Error", null);
        when(expire.execute(any())).thenReturn(Mono.just(new ExpirationResult(1, 0, List.of(expireFailure))));
        when(reconcile.execute(any())).thenReturn(Mono.just(new ReconciliationResult(0, 0, List.of(queryFailure))));

        ExpirationSummary summary = handler.handleRequest(SCHEDULED_EVENT, context);

        assertThat(summary.expired()).isEqualTo(1);
        assertThat(summary.failures()).isEqualTo(2);
    }

    @Test
    void passesTheRunInstantTraceIdAndTimeBudget() {
        when(expire.execute(any())).thenReturn(Mono.just(ExpirationResult.EMPTY));
        when(reconcile.execute(any())).thenReturn(Mono.just(ReconciliationResult.EMPTY));
        when(context.getRemainingTimeInMillis()).thenReturn(50_000, 9_999);

        handler.handleRequest(SCHEDULED_EVENT, context);

        ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(expire).execute(captor.capture());
        ExecutionContext execution = captor.getValue();
        assertThat(execution.now()).isEqualTo(Instant.parse("2026-10-16T10:12:00.123Z"));
        assertThat(execution.traceId()).matches("[0-9a-f]{32}");
        assertThat(execution.isOutOfTime()).isFalse();
        assertThat(execution.isOutOfTime()).isTrue();
        verify(reconcile).execute(execution);
    }

    @Test
    void requiresTheLambdaContext() {
        assertThatThrownBy(() -> handler.handleRequest(SCHEDULED_EVENT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void runtimeConstructorUsesTheRegisteredProvider() {
        ExpirationSummary summary = new ExpirerHandler().handleRequest(SCHEDULED_EVENT, context);

        assertThat(summary).isEqualTo(new ExpirationSummary(0, 0, 0, 0, 0));
    }

    @Test
    void loaderFailsWhenNoProviderIsRegistered() {
        assertThatThrownBy(() -> ExpirerComponentsLoader.load(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ExpirerComponentsProvider.class.getName());
    }

    @Test
    void componentsAreRequired() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new ExpirerComponents(null, reconcile, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExpirerComponents(expire, null, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExpirerComponents(expire, reconcile, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExpirerHandler(null)).isInstanceOf(NullPointerException.class);
    }
}
