package com.dbonilla.ticketing.expirer.model.message;

/**
 * Message types of contracts/messages/order-message.schema.json.
 */
public enum OrderMessageType {
    ORDER_PLACED("OrderPlaced"),
    ORDER_CONFIRMATION_REQUESTED("OrderConfirmationRequested");

    private final String contractName;

    OrderMessageType(String contractName) {
        this.contractName = contractName;
    }

    /** Name used in the message {@code type} field and the {@code messageType} attribute. */
    public String contractName() {
        return contractName;
    }
}
