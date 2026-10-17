package com.dbonilla.ticketing.expirer.usecase.reconcilestuckorders;

import com.dbonilla.ticketing.expirer.model.message.OrderMessageType;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;

/**
 * Stuck states and the message that unblocks them (R1 and R2).
 */
enum ReconciliationTarget {
    QUEUED(OrderStatus.QUEUED, OrderMessageType.ORDER_PLACED),
    PENDING_CONFIRMATION(OrderStatus.PENDING_CONFIRMATION, OrderMessageType.ORDER_CONFIRMATION_REQUESTED);

    private final OrderStatus status;
    private final OrderMessageType messageType;

    ReconciliationTarget(OrderStatus status, OrderMessageType messageType) {
        this.status = status;
        this.messageType = messageType;
    }

    OrderStatus status() {
        return status;
    }

    OrderMessageType messageType() {
        return messageType;
    }
}
