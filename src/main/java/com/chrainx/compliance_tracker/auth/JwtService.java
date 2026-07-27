package com.chrainx.compliance_tracker.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

// Everything JWT-specific lives here - generating signed tokens after login, and verifying +
// reading one on every subsequent request. A JWT is really just three base64 chunks
// (header.payload.signature) - the "payload" here is just the user's email (the "subject"),
// plus issued-at/expiry timestamps. The signature is what makes it trustworthy: anyone can
// *read* a JWT's payload (it's not encrypted, just encoded), but only someone holding this
// secret key can produce a signature that verifies successfully - so a client can't forge or
// tamper with a token without the signature check failing.
//
// Two kinds of token now (issue #26): a short-lived access token (attached to every normal API
// request) and a longer-lived refresh token (used only against POST /api/auth/refresh to get a
// new access token, without the user having to log in again). They're distinguished by a "type"
// claim - a refresh token embeds type=refresh, an access token doesn't - so
// JwtAuthenticationFilter can reject a refresh token presented as if it were an access token,
// and AuthController.refresh can reject an access token presented as if it were a refresh token.
@Component
public class JwtService {

    private static final String TYPE_CLAIM = "type";
    private static final String REFRESH_TYPE = "refresh";

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long accessExpirationMs,
                       @Value("${jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                // A random jti (JWT ID) on every token, not just relying on the issued-at
                // timestamp for uniqueness - standard JWT numeric-date claims only have
                // *second* precision, so two tokens generated for the same email within the
                // same second (e.g. two rapid refresh calls) would otherwise be byte-identical
                // signed strings. That matters concretely for TokenBlocklist/refresh rotation:
                // revoking one by exact string match would silently also revoke the "different"
                // one that happens to collide with it. Found this for real running a live
                // verification of the refresh endpoint, not hypothetically.
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(email)
                .id(UUID.randomUUID().toString())
                .claim(TYPE_CLAIM, REFRESH_TYPE)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    // Returns null if the token is missing, expired, or its signature doesn't verify - callers
    // treat null as "not authenticated"/"not a valid token" rather than needing to catch
    // exceptions themselves. Works for either kind of token - both carry the email as subject.
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

    // false for anything invalid too (garbage, expired, wrong signature) - a token that isn't
    // even genuinely verifiable certainly isn't a valid refresh token either.
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return REFRESH_TYPE.equals(claims.get(TYPE_CLAIM, String.class));
        } catch (Exception e) {
            return false;
        }
    }
}
