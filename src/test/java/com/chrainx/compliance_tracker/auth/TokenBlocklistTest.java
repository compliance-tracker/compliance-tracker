package com.chrainx.compliance_tracker.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBlocklistTest {

    private final TokenBlocklist blocklist = new TokenBlocklist();

    @Test
    void isRevoked_isFalse_forATokenThatWasNeverRevoked() {
        assertFalse(blocklist.isRevoked("some-token"));
    }

    @Test
    void isRevoked_isTrue_afterRevoke() {
        blocklist.revoke("some-token");

        assertTrue(blocklist.isRevoked("some-token"));
    }

    @Test
    void revoke_doesNotAffectADifferentToken() {
        blocklist.revoke("token-a");

        assertFalse(blocklist.isRevoked("token-b"));
    }
}
