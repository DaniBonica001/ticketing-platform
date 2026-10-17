package com.dbonilla.ticketing.processor.model.order;

/**
 * Lifecycle states of an order (see docs/design/domain-model.md, section 3).
 */
public enum OrderStatus {
    QUEUED, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY, REJECTED, EXPIRED;

    public boolean isFinal() {
        return switch (this) {
            case SOLD, COMPLIMENTARY, REJECTED, EXPIRED -> true;
            case QUEUED, RESERVED, PENDING_CONFIRMATION -> false;
        };
    }
}
