package com.dbonilla.ticketing.api.api.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.test.StepVerifier;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderFactoryTest {

    private static final String ISSUER = "https://auth.ticketing.local";
    private static final String AUDIENCE = "ticketing-api";

    @TempDir
    static Path directory;

    private static KeyPair keys;
    private static KeyPair otherKeys;
    private static ReactiveJwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        keys = rsa();
        otherKeys = rsa();
        Path pem = directory.resolve("jwt-public.pem");
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded());
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
        decoder = JwtDecoderFactory.create(new JwtProperties(ISSUER, AUDIENCE, new FileSystemResource(pem), null));
    }

    private static KeyPair rsa() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String token(KeyPair signer, UnaryOperator<JWTClaimsSet.Builder> customizer) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject("user-1").claim("scope", "ticketing:user")
                .notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)));
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), customizer.apply(claims).build());
        jwt.sign(new RSASSASigner(signer.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void acceptsValidToken() throws Exception {
        StepVerifier.create(decoder.decode(token(keys, UnaryOperator.identity())))
                .assertNext(jwt -> {
                    assertThat(jwt.getSubject()).isEqualTo("user-1");
                    assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
                })
                .verifyComplete();
    }

    @Test
    void toleratesThirtySecondsOfClockSkew() throws Exception {
        String token = token(keys, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(10))));

        StepVerifier.create(decoder.decode(token)).expectNextCount(1).verifyComplete();
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token(keys, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120))));

        StepVerifier.create(decoder.decode(token)).verifyError(JwtException.class);
    }

    @Test
    void rejectsTokenNotYetValid() throws Exception {
        String token = token(keys, claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(120))));

        StepVerifier.create(decoder.decode(token)).verifyError(JwtException.class);
    }

    @Test
    void rejectsWrongIssuerAudienceOrSubject() throws Exception {
        StepVerifier.create(decoder.decode(token(keys, claims -> claims.issuer("https://evil"))))
                .verifyError(JwtException.class);
        StepVerifier.create(decoder.decode(token(keys, claims -> claims.audience("other-api"))))
                .verifyError(JwtException.class);
        StepVerifier.create(decoder.decode(token(keys, claims -> claims.subject(null))))
                .verifyError(JwtException.class);
        StepVerifier.create(decoder.decode(token(keys, claims -> claims.expirationTime(null))))
                .verifyError(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        StepVerifier.create(decoder.decode(token(otherKeys, UnaryOperator.identity()))).verifyError(JwtException.class);
    }

    @Test
    void failsFastWhenThePublicKeyCannotBeRead() {
        Resource missing = new FileSystemResource(directory.resolve("missing.pem"));
        JwtProperties properties = new JwtProperties(ISSUER, AUDIENCE, missing, Duration.ofSeconds(5));

        assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> JwtDecoderFactory.create(properties)).isInstanceOf(UncheckedIOException.class);
        assertThatThrownBy(() -> new JwtProperties(null, AUDIENCE, missing, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JwtProperties(ISSUER, null, missing, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JwtProperties(ISSUER, AUDIENCE, null, null)).isInstanceOf(NullPointerException.class);
    }
}
