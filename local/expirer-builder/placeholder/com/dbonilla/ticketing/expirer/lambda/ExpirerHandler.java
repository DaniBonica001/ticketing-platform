package com.dbonilla.ticketing.expirer.lambda;

import java.util.Map;

/**
 * Placeholder with the same handler coordinates as the real expirer. It only proves that the
 * Lambda can be deployed and invoked by the EventBridge schedule; it does not touch any data.
 */
public final class ExpirerHandler {

    public Map<String, Object> handleRequest(Map<String, Object> event) {
        String source = event == null ? "direct" : String.valueOf(event.getOrDefault("source", "direct"));
        System.out.println("{\"message\":\"placeholder expirer invoked\",\"source\":\"" + source + "\"}");
        return Map.of("placeholder", true, "expired", 0, "reconciled", 0);
    }
}
