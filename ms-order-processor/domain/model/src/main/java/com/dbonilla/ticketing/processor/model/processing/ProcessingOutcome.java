package com.dbonilla.ticketing.processor.model.processing;

/**
 * Successful results of processing a message. Every outcome is final: the message can be acknowledged.
 */
public enum ProcessingOutcome {
    RESERVED,
    REJECTED_SOLD_OUT,
    SOLD,
    REJECTED_PAYMENT_DECLINED,
    /** The order was already processed (duplicate or re-delivered message). */
    ALREADY_PROCESSED
}
