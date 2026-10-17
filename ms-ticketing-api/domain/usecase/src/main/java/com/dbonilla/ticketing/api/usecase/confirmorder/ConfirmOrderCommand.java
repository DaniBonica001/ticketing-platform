package com.dbonilla.ticketing.api.usecase.confirmorder;

import com.dbonilla.ticketing.api.model.order.Caller;

public record ConfirmOrderCommand(Caller caller, String orderId, String paymentReference) {
}
