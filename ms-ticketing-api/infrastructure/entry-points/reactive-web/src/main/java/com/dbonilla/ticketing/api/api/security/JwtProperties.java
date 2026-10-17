package com.dbonilla.ticketing.api.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;
import java.util.Objects;

/**
 * JWT validation settings ({@code ticketing.security.jwt.*}). Only the public key is known by the API.
 */
@ConfigurationProperties(prefix = "ticketing.security.jwt")
public record JwtProperties(String issuer, String audience, Resource publicKeyLocation, Duration clockSkew) {

    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    public JwtProperties {
        Objects.requireNonNull(issuer, "ticketing.security.jwt.issuer");
        Objects.requireNonNull(audience, "ticketing.security.jwt.audience");
        Objects.requireNonNull(publicKeyLocation, "ticketing.security.jwt.public-key-location");
        clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
    }
}
