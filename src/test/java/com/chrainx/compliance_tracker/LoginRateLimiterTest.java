package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    private final LoginRateLimiter rateLimiter = new LoginRateLimiter();

    @Test
    void isBlocked_isFalse_forAnIpWithNoRecordedFailures() {
        assertFalse(rateLimiter.isBlocked("1.2.3.4"));
    }

    @Test
    void isBlocked_isFalse_belowTheFailureLimit() {
        for (int i = 0; i < 4; i++) {
            rateLimiter.recordFailure("1.2.3.4");
        }

        assertFalse(rateLimiter.isBlocked("1.2.3.4"));
    }

    @Test
    void isBlocked_isTrue_atTheFailureLimit() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure("1.2.3.4");
        }

        assertTrue(rateLimiter.isBlocked("1.2.3.4"));
    }

    @Test
    void isBlocked_isUnaffected_byFailuresOnADifferentIp() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure("1.2.3.4");
        }

        assertFalse(rateLimiter.isBlocked("5.6.7.8"));
    }

    @Test
    void recordSuccess_clearsThePriorFailureCount() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure("1.2.3.4");
        }
        assertTrue(rateLimiter.isBlocked("1.2.3.4"));

        rateLimiter.recordSuccess("1.2.3.4");

        assertFalse(rateLimiter.isBlocked("1.2.3.4"));
    }
}
