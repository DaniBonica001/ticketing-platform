package com.dbonilla.ticketing.expirer.dynamodb;

import com.dbonilla.ticketing.expirer.model.order.Order;
import com.dbonilla.ticketing.expirer.model.order.OrderPage;
import com.dbonilla.ticketing.expirer.model.order.OrderStatus;
import com.dbonilla.ticketing.expirer.model.order.PageCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.DEADLINE_AT;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.EVENT_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.ORDER_ID;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.QUANTITY;
import static com.dbonilla.ticketing.expirer.dynamodb.Attributes.STATUS;

/**
 * Maps index items to domain orders. Items are external data: a malformed item is logged and skipped so it
 * never blocks the rest of the page.
 */
final class OrderItemMapper {

    private static final Logger LOG = LogManager.getLogger(OrderItemMapper.class);

    OrderPage toPage(QueryResponse response) {
        List<Order> orders = response.items().stream()
                .map(OrderItemMapper::toOrder)
                .flatMap(Optional::stream)
                .toList();
        return new OrderPage(orders, cursor(response).orElse(null));
    }

    static Optional<Order> toOrder(Map<String, AttributeValue> item) {
        try {
            return Optional.of(new Order(
                    requiredText(item, ORDER_ID),
                    requiredText(item, EVENT_ID),
                    Integer.parseInt(requiredNumber(item, QUANTITY)),
                    OrderStatus.valueOf(requiredText(item, STATUS)),
                    Instant.ofEpochSecond(Long.parseLong(requiredNumber(item, DEADLINE_AT)))));
        } catch (IllegalArgumentException malformed) {
            LOG.warn("Skipping malformed order item orderId={} reason={}",
                    Optional.ofNullable(item.get(ORDER_ID)).map(AttributeValue::s).orElse("unknown"),
                    malformed.getMessage());
            return Optional.empty();
        }
    }

    static Optional<PageCursor> cursor(QueryResponse response) {
        if (!response.hasLastEvaluatedKey() || response.lastEvaluatedKey().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> key = response.lastEvaluatedKey();
        return Optional.of(new PageCursor(
                requiredText(key, ORDER_ID),
                OrderStatus.valueOf(requiredText(key, STATUS)),
                Instant.ofEpochSecond(Long.parseLong(requiredNumber(key, DEADLINE_AT)))));
    }

    private static String requiredText(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::s)
                .orElseThrow(() -> new IllegalArgumentException("missing string attribute " + name));
    }

    private static String requiredNumber(Map<String, AttributeValue> item, String name) {
        return Optional.ofNullable(item.get(name)).map(AttributeValue::n)
                .orElseThrow(() -> new IllegalArgumentException("missing number attribute " + name));
    }
}
