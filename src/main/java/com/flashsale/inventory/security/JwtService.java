package com.flashsale.inventory.security;

import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration expiration;
    // Thread-safe and immutable, so build it once instead of on every request.
    private final JwtParser parser;

    public JwtService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
        this.parser = Jwts.parser().verifyWith(key).build();
    }

    public String generateToken(Long userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration.toMillis()))
                .signWith(key)
                .compact();
    }

    // Throws JwtException if the signature is invalid or the token has expired,
    // and NumberFormatException if the subject is not a user id (e.g. a pre-id token).
    public Long extractUserId(String token) {
        String subject = parser.parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.parseLong(subject);
    }
}
