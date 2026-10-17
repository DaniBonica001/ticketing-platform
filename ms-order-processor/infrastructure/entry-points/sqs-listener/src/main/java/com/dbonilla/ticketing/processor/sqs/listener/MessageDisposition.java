package com.dbonilla.ticketing.processor.sqs.listener;

/**
 * What to do with a message after processing it.
 */
public enum MessageDisposition {
    /** Processed (success, duplicate or business rejection): delete it from the queue. */
    DELETE,
    /** Not processed: keep it so it is re-delivered after the visibility timeout and, eventually, sent to the DLQ. */
    RETAIN
}
