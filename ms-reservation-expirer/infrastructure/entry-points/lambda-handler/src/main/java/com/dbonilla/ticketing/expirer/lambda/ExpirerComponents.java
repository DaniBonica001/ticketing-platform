package com.dbonilla.ticketing.expirer.lambda;

import com.dbonilla.ticketing.expirer.model.metrics.gateways.RunMetricsPublisher;
import com.dbonilla.ticketing.expirer.usecase.expirereservations.ExpireReservationsUseCase;
import com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders.ReconcileStuckOrdersUseCase;

import java.time.Clock;
import java.util.Objects;

/**
 * Everything the handler needs, built once per execution environment by the composition root.
 */
public record ExpirerComponents(ExpireReservationsUseCase expireReservations,
                                ReconcileStuckOrdersUseCase reconcileStuckOrders,
                                Clock clock,
                                RunMetricsPublisher metrics) {

    public ExpirerComponents {
        Objects.requireNonNull(expireReservations, "expireReservations is required");
        Objects.requireNonNull(reconcileStuckOrders, "reconcileStuckOrders is required");
        Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(metrics, "metrics is required");
    }
}
