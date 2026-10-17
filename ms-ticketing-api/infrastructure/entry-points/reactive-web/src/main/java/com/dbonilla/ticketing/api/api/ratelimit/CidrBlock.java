package com.dbonilla.ticketing.api.api.ratelimit;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * IPv4 or IPv6 network in CIDR notation. A bare address is a host route (/32 or /128).
 */
final class CidrBlock {

    private static final int BITS_PER_BYTE = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final char PREFIX_SEPARATOR = '/';

    private final byte[] network;
    private final int prefixLength;

    private CidrBlock(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** @throws IllegalArgumentException when the value is not a CIDR block or an IP literal */
    static CidrBlock parse(String cidr) {
        String value = cidr.trim();
        int slash = value.indexOf(PREFIX_SEPARATOR);
        String address = slash < 0 ? value : value.substring(0, slash);
        byte[] bytes = IpLiterals.parse(address)
                .orElseThrow(() -> new IllegalArgumentException("Not an IP literal: '" + cidr + "'"))
                .getAddress();
        int maxPrefix = bytes.length * BITS_PER_BYTE;
        int prefix = slash < 0 ? maxPrefix : parsePrefix(value.substring(slash + 1), cidr);
        if (prefix < 0 || prefix > maxPrefix) {
            throw new IllegalArgumentException("Invalid prefix length: '" + cidr + "'");
        }
        return new CidrBlock(bytes, prefix);
    }

    private static int parsePrefix(String prefix, String cidr) {
        try {
            return Integer.parseInt(prefix);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length: '" + cidr + "'", e);
        }
    }

    boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / BITS_PER_BYTE;
        if (!Arrays.equals(candidate, 0, fullBytes, network, 0, fullBytes)) {
            return false;
        }
        int remainingBits = prefixLength % BITS_PER_BYTE;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (BYTE_MASK << (BITS_PER_BYTE - remainingBits)) & BYTE_MASK;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
