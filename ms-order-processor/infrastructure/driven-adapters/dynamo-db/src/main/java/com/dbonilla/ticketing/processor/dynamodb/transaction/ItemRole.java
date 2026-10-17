package com.dbonilla.ticketing.processor.dynamodb.transaction;

/**
 * Which table an item of a transaction writes to; used to interpret {@code CancellationReasons} by index.
 */
public enum ItemRole {
    EVENT, ORDER, AUDIT
}
