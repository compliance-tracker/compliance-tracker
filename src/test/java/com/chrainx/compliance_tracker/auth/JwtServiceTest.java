package com.chrainx.compliance_tracker.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 86_400_000L, 604_800_000L);

    @Test
    void generateAccessToken_thenExtractEmail_roundTrips() {
        String token = jwtService.generateAccessToken("someone@example.com");
        assertEquals("someone@example.com", jwtService.extractEmail(token));
    }

    @Test
    void generateRefreshToken_thenExtractEmail_roundTrips() {
        String token = jwtService.generateRefreshToken("someone@example.com");
        assertEquals("someone@example.com", jwtService.extractEmail(token));
    }

    @Test
    void extractEmail_returnsNull_forGarbageToken() {
        assertNull(jwtService.extractEmail("not-a-real-jwt"));
    }

    @Test
    void extractEmail_returnsNull_whenSignedWithADifferentSecret() {
        JwtService otherService = new JwtService(
                "B+lxGhY4SYh62vCKwugwvtrft0JLe0w98SSWcDRckMk=", 86_400_000L, 604_800_000L);
        String token = otherService.generateAccessToken("someone@example.com");

        // Verifies the signature actually matters - a token can't be forged/reused across a
        // different signing key, which is the whole point of signing it in the first place.
        assertNull(jwtService.extractEmail(token));
    }

    @Test
    void extractEmail_returnsNull_forExpiredToken() throws InterruptedException {
        JwtService shortLivedService = new JwtService(
                "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 1L, 1L);
        String token = shortLivedService.generateAccessToken("someone@example.com");
        Thread.sleep(10);

        assertNull(shortLivedService.extractEmail(token));
    }

    @Test
    void isRefreshToken_isTrue_forARefreshToken() {
        String token = jwtService.generateRefreshToken("someone@example.com");
        assertTrue(jwtService.isRefreshToken(token));
    }

    @Test
    void isRefreshToken_isFalse_forAnAccessToken() {
        // The actual security-relevant distinction (issue #26) - an access token must never be
        // usable as a refresh token, or the whole point of having two different lifetimes and
        // purposes falls apart.
        String token = jwtService.generateAccessToken("someone@example.com");
        assertFalse(jwtService.isRefreshToken(token));
    }

    @Test
    void isRefreshToken_isFalse_forAGarbageToken() {
        assertFalse(jwtService.isRefreshToken("not-a-real-jwt"));
    }

    @Test
    void generateRefreshToken_producesADifferentToken_evenForTheSameEmailCalledTwiceImmediately() {
        // Regression test: standard JWT numeric-date claims only have *second* precision, so
        // two tokens for the same email generated within the same second used to be
        // byte-identical strings (no jti/other differentiator) - which broke refresh rotation
        // in practice: revoking the old refresh token by exact string match would also silently
        // revoke the "new" one just issued to replace it, since they were the same string.
        // Found live, not hypothetically, running the real refresh endpoint by hand.
        String first = jwtService.generateRefreshToken("someone@example.com");
        String second = jwtService.generateRefreshToken("someone@example.com");

        assertFalse(first.equals(second), "two tokens generated back-to-back must not be identical");
    }

    @Test
    void generateAccessToken_producesADifferentToken_evenForTheSameEmailCalledTwiceImmediately() {
        String first = jwtService.generateAccessToken("someone@example.com");
        String second = jwtService.generateAccessToken("someone@example.com");

        assertFalse(first.equals(second), "two tokens generated back-to-back must not be identical");
    }
}
