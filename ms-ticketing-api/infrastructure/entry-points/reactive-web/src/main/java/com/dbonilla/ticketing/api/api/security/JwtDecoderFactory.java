package com.dbonilla.ticketing.api.api.security;

import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * RS256 decoder that validates signature, {@code iss}, {@code aud}, {@code exp}, {@code nbf} (with clock
 * skew) and requires {@code sub}.
 */
public final class JwtDecoderFactory {

    private JwtDecoderFactory() {
    }

    public static ReactiveJwtDecoder create(JwtProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(readPublicKey(properties))
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(JwtProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(properties.clockSkew()),
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank())));
    }

    private static RSAPublicKey readPublicKey(JwtProperties properties) {
        try (InputStream pem = properties.publicKeyLocation().getInputStream()) {
            return RsaKeyConverters.x509().convert(pem);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the JWT public key", e);
        }
    }
}
