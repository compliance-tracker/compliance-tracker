package com.chrainx.compliance_tracker.auth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // Real JwtService (not mocked) - it's cheap, deterministic, and testing against a mock
    // would just be testing that the mock returns what we told it to.
    private final JwtService jwtService = new JwtService(
            "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 86_400_000L, 604_800_000L);
    // Real LoginRateLimiter (not mocked) - same reasoning as JwtService above, and a fresh
    // instance per test method (JUnit 5 creates a new test instance for each @Test by default)
    // means no attempt counts leak between tests.
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter();
    // Real TokenBlocklist, same reasoning - fresh instance per test, no revoked tokens leak
    // between tests.
    private final TokenBlocklist tokenBlocklist = new TokenBlocklist();

    private final AuthController controller = new AuthController(userRepository, passwordEncoder, jwtService, loginRateLimiter, tokenBlocklist);

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void register_savesNewUser_andReturnsAToken() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("new@example.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().token());
        assertEquals("new@example.com", jwtService.extractEmail(response.getBody().token()));
        assertFalse(jwtService.isRefreshToken(response.getBody().token()));
        assertNotNull(response.getBody().refreshToken());
        assertTrue(jwtService.isRefreshToken(response.getBody().refreshToken()));
    }

    @Test
    void register_returns409_whenEmailAlreadyTaken() {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(new User()));

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("taken@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void register_returns409_whenSaveHitsTheUniqueConstraint() {
        // Regression test for issue #42: simulates the race window where findByEmail returns
        // empty (no concurrent request has committed yet) but save() still fails, because
        // another request for the same email won the race and committed first. Without
        // catching this, it would surface as an unhandled 500, not the same clean 409 the
        // sequential-request case above returns.
        when(userRepository.findByEmail("racing@example.com")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(userRepository).save(any());

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("racing@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void register_returns400_whenPasswordIsTooShort() {
        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("new@example.com", "abc123"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void register_returns400_whenPasswordHasNoDigit() {
        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("new@example.com", "allletters"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void register_returns400_whenPasswordHasNoLetter() {
        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("new@example.com", "12345678"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void register_checksPasswordStrength_beforeCheckingIfEmailIsTaken() {
        // A weak password should be rejected as a 400 regardless of whether the email is
        // available - malformed input takes precedence over a business-logic conflict, and this
        // also proves the check happens without ever needing to hit the repository at all.
        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("taken@example.com", "weak"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(userRepository);
    }

    @Test
    void login_returnsAToken_whenPasswordMatches() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<AuthResponse> response = controller.login(
                new AuthRequest("owner@example.com", "correct-password"), requestFrom("10.0.0.1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", jwtService.extractEmail(response.getBody().token()));
        assertTrue(jwtService.isRefreshToken(response.getBody().refreshToken()));
    }

    @Test
    void login_returns401_whenPasswordIsWrong() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<AuthResponse> response = controller.login(
                new AuthRequest("owner@example.com", "wrong-password"), requestFrom("10.0.0.2"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_returns401_whenEmailDoesNotExist() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        ResponseEntity<AuthResponse> response = controller.login(
                new AuthRequest("nobody@example.com", "anything"), requestFrom("10.0.0.3"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_returns429_afterFiveFailedAttemptsFromTheSameIp() {
        // Regression test for issue #35: repeated password-guessing against a known email had
        // nothing stopping it. The 6th attempt from the same IP within the window should be
        // rejected before even checking credentials.
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestFrom("10.0.0.4");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<AuthResponse> response = controller.login(new AuthRequest("victim@example.com", "guess" + i), request);
            assertEquals(401, response.getStatusCode().value());
        }

        ResponseEntity<AuthResponse> sixthAttempt = controller.login(new AuthRequest("victim@example.com", "guess5"), request);

        assertEquals(429, sixthAttempt.getStatusCode().value());
    }

    @Test
    void login_isNotRateLimited_forADifferentIp() {
        // A different attacker IP (or a different legitimate user entirely) must not be
        // affected by another IP's failed attempts - rate limiting is per-IP, not global.
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        for (int i = 0; i < 5; i++) {
            controller.login(new AuthRequest("victim@example.com", "guess" + i), requestFrom("10.0.0.5"));
        }

        ResponseEntity<AuthResponse> response = controller.login(
                new AuthRequest("victim@example.com", "another-guess"), requestFrom("10.0.0.6"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_succeedingResetsTheFailureCount_forThatIp() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        MockHttpServletRequest request = requestFrom("10.0.0.7");

        controller.login(new AuthRequest("owner@example.com", "wrong-password"), request);
        controller.login(new AuthRequest("owner@example.com", "wrong-password"), request);
        ResponseEntity<AuthResponse> successResponse = controller.login(new AuthRequest("owner@example.com", "correct-password"), request);
        assertEquals(200, successResponse.getStatusCode().value());

        // Failure count should be back to zero after the success above - four more wrong
        // attempts (fewer than the limit) should still be plain 401s, not 429.
        for (int i = 0; i < 4; i++) {
            ResponseEntity<AuthResponse> response = controller.login(new AuthRequest("owner@example.com", "wrong-again"), request);
            assertEquals(401, response.getStatusCode().value());
        }
    }

    private MockHttpServletRequest requestWithAuthHeader(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader("Authorization", headerValue);
        }
        return request;
    }

    @Test
    void logout_revokesTheToken() {
        String token = jwtService.generateAccessToken("owner@example.com");

        ResponseEntity<Void> response = controller.logout(requestWithAuthHeader("Bearer " + token));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(tokenBlocklist.isRevoked(token));
    }

    @Test
    void logout_returns400_whenNoAuthorizationHeaderPresent() {
        ResponseEntity<Void> response = controller.logout(requestWithAuthHeader(null));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void logout_returns400_whenHeaderDoesNotStartWithBearer() {
        ResponseEntity<Void> response = controller.logout(requestWithAuthHeader("Basic somecreds"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void refresh_issuesANewAccessAndRefreshToken_forAValidRefreshToken() {
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        String refreshToken = jwtService.generateRefreshToken("owner@example.com");

        ResponseEntity<AuthResponse> response = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", jwtService.extractEmail(response.getBody().token()));
        assertFalse(jwtService.isRefreshToken(response.getBody().token()));
        assertTrue(jwtService.isRefreshToken(response.getBody().refreshToken()));
    }

    @Test
    void refresh_revokesTheOldRefreshToken_soItCannotBeReused() {
        // The actual rotation behaviour (issue #26): a refresh token is single-use. Reusing one
        // that's already been exchanged must fail, the same way a stolen-and-already-used one
        // would for an attacker who got there second.
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        String refreshToken = jwtService.generateRefreshToken("owner@example.com");

        controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));
        ResponseEntity<AuthResponse> secondAttempt = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(401, secondAttempt.getStatusCode().value());
    }

    @Test
    void refresh_returns401_whenGivenAnAccessTokenInstead() {
        // The other half of the type-separation check: an access token must not work as a
        // refresh token either, or the distinction between the two is meaningless.
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        String accessToken = jwtService.generateAccessToken("owner@example.com");

        ResponseEntity<AuthResponse> response = controller.refresh(requestWithAuthHeader("Bearer " + accessToken));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refresh_returns401_whenTheAccountNoLongerExists() {
        when(userRepository.findByEmail("deleted@example.com")).thenReturn(Optional.empty());
        String refreshToken = jwtService.generateRefreshToken("deleted@example.com");

        ResponseEntity<AuthResponse> response = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refresh_returns400_whenNoAuthorizationHeaderPresent() {
        ResponseEntity<AuthResponse> response = controller.refresh(requestWithAuthHeader(null));

        assertEquals(400, response.getStatusCode().value());
    }
}
