package com.bindass.backend.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

// Handles JWT creation and validation
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration; // in milliseconds

    /** Refuse to start with a missing / short / obviously-placeholder signing key. */
    @PostConstruct
    public void validateSecret() {
        String s = jwtSecret == null ? "" : jwtSecret.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 characters. Set JWT_SECRET (e.g. `openssl rand -base64 48`).");
        }
        if (lower.contains("change") || lower.contains("your-secret") || lower.contains("example")
                || lower.contains("placeholder") || lower.startsWith("secret")) {
            throw new IllegalStateException(
                    "app.jwt.secret looks like a placeholder. Generate a random value (e.g. `openssl rand -base64 48`).");
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.trim().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiration);

        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public String getEmailFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
