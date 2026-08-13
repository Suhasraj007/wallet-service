package com.wallet.auth;

import com.wallet.exception.UnauthorizedException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 JWTs. The /auth/token endpoint is a deliberately
 * minimal stand-in for a real identity provider; the wallet endpoints trust
 * nothing except a token whose signature verifies against JWT_SECRET.
 */
@Service
public class JwtService {

    private static final String ISSUER = "wallet-service";

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${auth.jwt-secret}") String secret,
                      @Value("${auth.token-ttl-seconds}") long ttlSeconds) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 32 bytes for HS256; refusing to start");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Returns the verified caller's user id, or throws UnauthorizedException.
     * Signature, issuer and expiry are all checked by the parser.
     */
    public String verify(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            if (subject == null || subject.isBlank()) {
                throw new UnauthorizedException("token_missing_subject");
            }
            return subject;
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("invalid_token");
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
