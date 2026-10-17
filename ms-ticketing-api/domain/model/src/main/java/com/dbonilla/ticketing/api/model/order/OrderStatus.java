package com.dbonilla.ticketing.api.model.order;

public enum OrderStatus {
    QUEUED, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY, REJECTED, EXPIRED;

    public boolean isFinal() {
        return switch (this) {
            case SOLD, COMPLIMENTARY, REJECTED, EXPIRED -> true;
            case QUEUED, RESERVED, PENDING_CONFIRMATION -> false;
        };
    }
}
