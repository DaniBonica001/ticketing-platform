package com.dbonilla.ticketing.expirer.lambda;

import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpirationResult;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpireReservationsUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconcileStuckOrdersUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconciliationResult;
import reactor.core.publisher.Mono;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Registered in test META-INF/services to exercise the Lambda runtime's no-arg constructor. */
public class StubComponentsProvider implements ExpirerComponentsProvider {

    @Override
    public ExpirerComponents create() {
        ExpireReservationsUseCase expire = mock(ExpireReservationsUseCase.class);
        ReconcileStuckOrdersUseCase reconcile = mock(ReconcileStuckOrdersUseCase.class);
        when(expire.execute(any())).thenReturn(Mono.just(ExpirationResult.EMPTY));
        when(reconcile.execute(any())).thenReturn(Mono.just(ReconciliationResult.EMPTY));
        return new ExpirerComponents(expire, reconcile, Clock.systemUTC(), RunMetricsPublisher.NOOP);
    }
}
