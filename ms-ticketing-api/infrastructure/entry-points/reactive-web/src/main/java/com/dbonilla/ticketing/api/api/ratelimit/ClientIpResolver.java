package com.dbonilla.ticketing.api.api.ratelimit;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;

/**
 * Client IP used as the rate-limit key. The TCP peer is the client unless it is a trusted proxy: only then is
 * the client IP taken from {@code clientIpHeader}, which the edge (API Gateway) overwrites with the caller's
 * source IP. A header sent by an untrusted peer, or a value that is not an IP literal, is ignored.
 */
final class ClientIpResolver {

    static final String UNKNOWN_CLIENT = "unknown";
    private static final String PROPERTY = "ticketing.rate-limit.trusted-proxies";

    private final List<CidrBlock> trustedProxies;
    private final String clientIpHeader;

    ClientIpResolver(List<String> trustedProxies, String clientIpHeader) {
        this.trustedProxies = trustedProxies.stream().map(ClientIpResolver::parseTrusted).toList();
        this.clientIpHeader = clientIpHeader;
    }

    private static CidrBlock parseTrusted(String cidr) {
        try {
            return CidrBlock.parse(cidr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(PROPERTY + ": " + e.getMessage(), e);
        }
    }

    String resolve(ServerHttpRequest request) {
        Optional<InetAddress> peer = Optional.ofNullable(request.getRemoteAddress())
                .map(InetSocketAddress::getAddress);
        if (peer.isEmpty()) {
            return UNKNOWN_CLIENT;
        }
        InetAddress client = isTrusted(peer.get()) ? forwardedClient(request).orElse(peer.get()) : peer.get();
        return client.getHostAddress();
    }

    private boolean isTrusted(InetAddress peer) {
        return trustedProxies.stream().anyMatch(block -> block.contains(peer));
    }

    private Optional<InetAddress> forwardedClient(ServerHttpRequest request) {
        return IpLiterals.parse(request.getHeaders().getFirst(clientIpHeader));
    }
}
