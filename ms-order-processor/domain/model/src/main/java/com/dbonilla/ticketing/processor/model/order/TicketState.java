package com.dbonilla.ticketing.processor.model.order;

/**
 * States of a single ticket; each one is an inventory counter of the event.
 */
public enum TicketState {
    AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY
}
