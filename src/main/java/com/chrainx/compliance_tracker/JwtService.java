package com.chrainx.compliance_tracker;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

// Everything JWT-specific lives here - generating a signed token after login, and verifying +
// reading one on every subsequent request. A JWT is really just three base64 chunks
// (header.payload.signature) - the "payload" here is just the user's email (the "subject"),
// plus issued-at/expiry timestamps. The signature is what makes it trustworthy: anyone can
// *read* a JWT's payload (it's not encrypted, just encoded), but only someone holding this
// secret key can produce a signature that verifies successfully - so a client can't forge or
// tamper with a token without the signature check failing.
@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    // Returns null if the token is missing, expired, or its signature doesn't verify - callers
    // (JwtAuthenticationFilter) treat null as "not authenticated" rather than needing to catch
    // exceptions themselves.
    public String extractEmail(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
