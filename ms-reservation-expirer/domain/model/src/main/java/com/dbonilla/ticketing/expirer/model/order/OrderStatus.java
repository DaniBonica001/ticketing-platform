package com.dbonilla.ticketing.expirer.model.order;

/**
 * Order lifecycle states (see docs/design/domain-model.md, section 3).
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
