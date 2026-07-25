package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 86_400_000L);

    @Test
    void generateToken_thenExtractEmail_roundTrips() {
        String token = jwtService.generateToken("someone@example.com");
        assertEquals("someone@example.com", jwtService.extractEmail(token));
    }

    @Test
    void extractEmail_returnsNull_forGarbageToken() {
        assertNull(jwtService.extractEmail("not-a-real-jwt"));
    }

    @Test
    void extractEmail_returnsNull_whenSignedWithADifferentSecret() {
        JwtService otherService = new JwtService(
                "B+lxGhY4SYh62vCKwugwvtrft0JLe0w98SSWcDRckMk=", 86_400_000L);
        String token = otherService.generateToken("someone@example.com");

        // Verifies the signature actually matters - a token can't be forged/reused across a
        // different signing key, which is the whole point of signing it in the first place.
        assertNull(jwtService.extractEmail(token));
    }

    @Test
    void extractEmail_returnsNull_forExpiredToken() throws InterruptedException {
        JwtService shortLivedService = new JwtService(
                "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 1L);
        String token = shortLivedService.generateToken("someone@example.com");
        Thread.sleep(10);

        assertNull(shortLivedService.extractEmail(token));
    }
}
