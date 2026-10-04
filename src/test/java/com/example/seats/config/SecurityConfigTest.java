package com.example.seats.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.*;

class SecurityConfigTest {
    private static final String SECRET = "a-long-random-test-key-with-at-least-32-bytes";
    private final org.springframework.security.oauth2.jwt.JwtDecoder decoder =
            new SecurityConfig().jwtDecoder(SECRET, "seat-reservation", "seat-api");

    @Test
    void acceptsValidTokenAndPreservesSubject() throws Exception {
        var token = sign(claims().build(), SECRET);
        assertThat(decoder.decode(token).getSubject()).isEqualTo("alice");
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        reject(claims().build(), "another-long-secret-not-the-real-signing-key");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        reject(claims().expirationTime(Date.from(Instant.now().minusSeconds(300))).build(), SECRET);
    }

    @Test
    void rejectsWrongIssuerAndAudience() throws Exception {
        reject(claims().issuer("untrusted").build(), SECRET);
        reject(claims().audience("other-service").build(), SECRET);
    }

    @Test
    void rejectsMissingExpiryAndInvalidSubject() throws Exception {
        reject(claims().expirationTime(null).build(), SECRET);
        reject(claims().subject(null).build(), SECRET);
        reject(claims().subject(" ").build(), SECRET);
        reject(claims().subject("a".repeat(201)).build(), SECRET);
    }

    @Test
    void refusesWeakSigningKeyAtStartup() {
        assertThatThrownBy(() -> new SecurityConfig().jwtDecoder("short", "issuer", "audience"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().subject("alice").issuer("seat-reservation").audience("seat-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)));
    }

    private void reject(JWTClaimsSet claims, String secret) throws Exception {
        String token = sign(claims, secret);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private String sign(JWTClaimsSet claims, String secret) throws Exception {
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(secret));
        return token.serialize();
    }
}
