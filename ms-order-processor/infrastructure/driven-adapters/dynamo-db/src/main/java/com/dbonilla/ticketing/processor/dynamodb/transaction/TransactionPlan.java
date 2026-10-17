package com.dbonilla.ticketing.processor.dynamodb.transaction;

import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import java.util.List;

/**
 * A transaction request together with the role of each item, in the same order as the request items.
 */
public record TransactionPlan(List<ItemRole> roles, List<TransactWriteItem> items) {

    public TransactionPlan {
        roles = List.copyOf(roles);
        items = List.copyOf(items);
        if (roles.size() != items.size()) {
            throw new IllegalArgumentException("Every transaction item needs a role");
        }
    }

    public ItemRole roleAt(int index) {
        return roles.get(index);
    }

    public TransactWriteItemsRequest toRequest() {
        return TransactWriteItemsRequest.builder().transactItems(items).build();
    }
}
