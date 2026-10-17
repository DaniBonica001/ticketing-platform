package com.dbonilla.ticketing.api.api.ratelimit;

import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict IP literal parsing: never resolves host names (no DNS lookups on request data).
 */
final class IpLiterals {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");
    private static final char IPV6_SEPARATOR = ':';

    private IpLiterals() {
    }

    /** The address when {@code value} is an IPv4 (dotted quad) or IPv6 literal; empty otherwise. */
    static Optional<InetAddress> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = value.trim();
        Pattern shape = candidate.indexOf(IPV6_SEPARATOR) >= 0 ? IPV6 : IPV4;
        if (!shape.matcher(candidate).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(candidate));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
