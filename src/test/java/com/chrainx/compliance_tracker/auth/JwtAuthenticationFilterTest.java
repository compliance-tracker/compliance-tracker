package com.chrainx.compliance_tracker.auth;

import com.chrainx.compliance_tracker.security.EmailHasher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Only isValidForUser is exercised directly here (package-private specifically for this) -
// the rest of doFilterInternal is standard OncePerRequestFilter plumbing already covered
// end to end by AuthIntegrationTest's real HTTP calls.
class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TokenBlocklist tokenBlocklist = mock(TokenBlocklist.class);
    // Real instance, not mocked - EmailHasher is pure logic (issue #63), same reasoning as
    // RuleEngine being used as-is elsewhere in this project's mocked-repository unit tests.
    private final EmailHasher emailHasher = new EmailHasher("I9FNAHshRkw+oPgsfjRlvm+F3SNRE30qlcWwcY5Tn7A=");
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtService, userRepository, tokenBlocklist, emailHasher);

    @Test
    void isValidForUser_isTrue_whenUserHasNeverResetTheirPassword() {
        User user = new User();
        user.setTokenValidAfter(null);

        assertTrue(filter.isValidForUser("any-token", user));
    }

    @Test
    void isValidForUser_isTrue_whenTokenWasIssuedAfterTheReset() {
        User user = new User();
        Instant resetAt = Instant.now();
        user.setTokenValidAfter(resetAt);
        when(jwtService.extractIssuedAt("token")).thenReturn(Date.from(resetAt.plusSeconds(5)));

        assertTrue(filter.isValidForUser("token", user));
    }

    @Test
    void isValidForUser_isTrue_whenTokenWasIssuedInTheSameSecondAsTheReset() {
        // Login right after a reset mints a token whose issued-at (a JWT "iat" claim only has
        // *second* precision - see JwtAuthenticationFilter.isValidForUser's own comment) can
        // land in the same second as tokenValidAfter's own sub-second Instant.now() - that token
        // must still be accepted, or a reset would lock the user out of the very session they
        // just created.
        User user = new User();
        Instant resetAt = Instant.now();
        user.setTokenValidAfter(resetAt);
        // Simulates a real token's issued-at, which would have been truncated to the second by
        // the JWT round-trip itself (see JwtServiceTest.extractIssuedAt_...) - not the untruncated
        // resetAt a naive test would otherwise compare against.
        when(jwtService.extractIssuedAt("token")).thenReturn(Date.from(resetAt.truncatedTo(ChronoUnit.SECONDS)));

        assertTrue(filter.isValidForUser("token", user));
    }

    @Test
    void isValidForUser_isFalse_whenTokenWasIssuedBeforeTheReset() {
        User user = new User();
        Instant resetAt = Instant.now();
        user.setTokenValidAfter(resetAt);
        when(jwtService.extractIssuedAt("token")).thenReturn(Date.from(resetAt.minusSeconds(5)));

        assertFalse(filter.isValidForUser("token", user));
    }

    @Test
    void isValidForUser_isFalse_whenIssuedAtCannotBeRead() {
        User user = new User();
        user.setTokenValidAfter(Instant.now());
        when(jwtService.extractIssuedAt("token")).thenReturn(null);

        assertFalse(filter.isValidForUser("token", user));
    }
}
