package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    // Real JwtService (not mocked) - it's cheap, deterministic, and testing against a mock
    // would just be testing that the mock returns what we told it to.
    private final JwtService jwtService = new JwtService(
            "04r6vb/78+5VZS4jQPmJ1P669O0ZxGQ5veyDTW+QpW4=", 86_400_000L);
    // Real LoginRateLimiter (not mocked) - same reasoning as JwtService above, and a fresh
    // instance per test method (JUnit 5 creates a new test instance for each @Test by default)
    // means no attempt counts leak between tests.
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter();

    private final AuthController controller = new AuthController(userRepository, passwordEncoder, jwtService, loginRateLimiter);

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
    }

    @Test
    void register_returns409_whenEmailAlreadyTaken() {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(new User()));

        ResponseEntity<AuthResponse> response = controller.register(new AuthRequest("taken@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
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
}
