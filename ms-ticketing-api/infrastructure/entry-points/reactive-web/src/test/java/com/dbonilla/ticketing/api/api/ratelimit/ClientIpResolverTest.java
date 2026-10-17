package com.dbonilla.ticketing.api.api.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final String HEADER = "X-Client-Ip";
    private static final ClientIpResolver BEHIND_PROXY =
            new ClientIpResolver(List.of("10.0.0.0/16", "fd00:10::/64"), HEADER);

    private static MockServerHttpRequest request(String peer, String headerValue) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/v1/events")
                .remoteAddress(new InetSocketAddress(peer, 443));
        if (headerValue != null) {
            builder.header(HEADER, headerValue);
        }
        return builder.build();
    }

    @Test
    void untrustedPeerCannotSpoofTheClientIp() {
        assertThat(BEHIND_PROXY.resolve(request("203.0.113.7", "198.51.100.1"))).isEqualTo("203.0.113.7");
    }

    @Test
    void withoutTrustedProxiesTheHeaderIsAlwaysIgnored() {
        ClientIpResolver direct = new ClientIpResolver(List.of(), HEADER);

        assertThat(direct.resolve(request("10.0.1.5", "198.51.100.1"))).isEqualTo("10.0.1.5");
    }

    @Test
    void trustedPeerForwardsTheClientIpFromTheHeader() {
        assertThat(BEHIND_PROXY.resolve(request("10.0.1.5", "198.51.100.1"))).isEqualTo("198.51.100.1");
        assertThat(BEHIND_PROXY.resolve(request("10.0.1.5", " 198.51.100.2 "))).isEqualTo("198.51.100.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-an-ip", "example.com", "198.51.100.1, 10.0.0.1", "999.1.1.1",
            "198.51.100.1:8080", "1.2.3"})
    void trustedPeerWithInvalidHeaderFallsBackToThePeer(String value) {
        assertThat(BEHIND_PROXY.resolve(request("10.0.1.5", value))).isEqualTo("10.0.1.5");
    }

    @Test
    void trustedPeerWithoutHeaderFallsBackToThePeer() {
        assertThat(BEHIND_PROXY.resolve(request("10.0.1.5", null))).isEqualTo("10.0.1.5");
    }

    @Test
    void supportsIpv6PeersAndClients() {
        assertThat(BEHIND_PROXY.resolve(request("fd00:10::5", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(BEHIND_PROXY.resolve(request("fd00:10:0:1::5", "2001:db8::1"))).isEqualTo("fd00:10:0:1:0:0:0:5");
        assertThat(BEHIND_PROXY.resolve(request("10.0.1.5", "2001:db8::1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void singleAddressesAreTrustedAsHostRoutes() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.9"), HEADER);

        assertThat(resolver.resolve(request("10.0.0.9", "198.51.100.1"))).isEqualTo("198.51.100.1");
        assertThat(resolver.resolve(request("10.0.0.10", "198.51.100.1"))).isEqualTo("10.0.0.10");
    }

    @Test
    void unknownPeerIsReportedAsUnknown() {
        MockServerHttpRequest withoutPeer = MockServerHttpRequest.get("/api/v1/events").header(HEADER, "1.1.1.1")
                .build();

        assertThat(BEHIND_PROXY.resolve(withoutPeer)).isEqualTo("unknown");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "fd00::/129", "10.0.0.0/-1", "10.0.0.0/x", "vpc.internal/16", "/16", ""})
    void invalidTrustedProxiesFailFast(String cidr) {
        assertThatThrownBy(() -> new ClientIpResolver(List.of(cidr), HEADER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticketing.rate-limit.trusted-proxies");
    }
}
