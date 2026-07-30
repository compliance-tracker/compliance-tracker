package com.chrainx.compliance_tracker.auth;

import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.notifications.AuthEmailSender;
import com.chrainx.compliance_tracker.security.EmailHasher;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final PasswordResetTokenRepository passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
    private final EmailVerificationTokenRepository emailVerificationTokenRepository = mock(EmailVerificationTokenRepository.class);
    private final AuthEmailSender authEmailSender = mock(AuthEmailSender.class);
    // Real instance, not mocked - EmailHasher is pure logic (issue #63), same reasoning as
    // JwtService/LoginRateLimiter/TokenBlocklist above.
    private final EmailHasher emailHasher = new EmailHasher("I9FNAHshRkw+oPgsfjRlvm+F3SNRE30qlcWwcY5Tn7A=");

    private final AuthController controller = new AuthController(userRepository, passwordEncoder, jwtService,
            loginRateLimiter, tokenBlocklist, passwordResetTokenRepository, emailVerificationTokenRepository,
            authEmailSender, emailHasher, 3_600_000L, 604_800_000L);

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    // Controller methods now return ResponseEntity<?> (issue #47 - the success body and the
    // ApiError error body are different types), so tests cast the body to whichever shape a
    // given response actually is.
    private AuthResponse authBody(ResponseEntity<?> response) {
        return (AuthResponse) response.getBody();
    }

    private ApiError errorBody(ResponseEntity<?> response) {
        return (ApiError) response.getBody();
    }

    @Test
    void register_savesNewUser_andDoesNotReturnUsableTokens() {
        // Issue #120 (expanded scope): register used to return real, immediately usable tokens -
        // it no longer does, since an unverified account can't log in anyway now (login itself
        // enforces verification, see the login_* tests below). Just a confirmation message.
        when(userRepository.findByEmailHash(emailHasher.hash("new@example.com"))).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.register(new AuthRequest("new@example.com", "password123"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(((RegistrationResponse) response.getBody()).message());
        verify(userRepository).save(argThat(user -> user.getEmail().equals("new@example.com")));
    }

    @Test
    void register_returns409_whenEmailAlreadyTaken() {
        when(userRepository.findByEmailHash(emailHasher.hash("taken@example.com"))).thenReturn(Optional.of(new User()));

        ResponseEntity<?> response = controller.register(new AuthRequest("taken@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONFLICT", errorBody(response).error());
    }

    @Test
    void register_returns409_whenSaveHitsTheUniqueConstraint() {
        // Regression test for issue #42: simulates the race window where findByEmail returns
        // empty (no concurrent request has committed yet) but save() still fails, because
        // another request for the same email won the race and committed first. Without
        // catching this, it would surface as an unhandled 500, not the same clean 409 the
        // sequential-request case above returns.
        when(userRepository.findByEmailHash(emailHasher.hash("racing@example.com"))).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(userRepository).save(any());

        ResponseEntity<?> response = controller.register(new AuthRequest("racing@example.com", "password123"));

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void register_returns400_whenPasswordIsTooShort() {
        ResponseEntity<?> response = controller.register(new AuthRequest("new@example.com", "abc123"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("BAD_REQUEST", errorBody(response).error());
    }

    @Test
    void register_returns400_whenPasswordHasNoDigit() {
        ResponseEntity<?> response = controller.register(new AuthRequest("new@example.com", "allletters"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void register_returns400_whenPasswordHasNoLetter() {
        ResponseEntity<?> response = controller.register(new AuthRequest("new@example.com", "12345678"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void register_checksPasswordStrength_beforeCheckingIfEmailIsTaken() {
        // A weak password should be rejected as a 400 regardless of whether the email is
        // available - malformed input takes precedence over a business-logic conflict, and this
        // also proves the check happens without ever needing to hit the repository at all.
        ResponseEntity<?> response = controller.register(new AuthRequest("taken@example.com", "weak"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(userRepository);
    }

    @Test
    void login_returnsAToken_whenPasswordMatches() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setEmailVerified(true);
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.login(
                new AuthRequest("owner@example.com", "correct-password"), requestFrom("10.0.0.1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", jwtService.extractEmail(authBody(response).token()));
        assertTrue(jwtService.isRefreshToken(authBody(response).refreshToken()));
    }

    @Test
    void login_returns401_whenPasswordIsWrong() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.login(
                new AuthRequest("owner@example.com", "wrong-password"), requestFrom("10.0.0.2"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(response).error());
    }

    @Test
    void login_returns401_whenEmailDoesNotExist() {
        when(userRepository.findByEmailHash(any())).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.login(
                new AuthRequest("nobody@example.com", "anything"), requestFrom("10.0.0.3"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_returns429_afterFiveFailedAttemptsFromTheSameIp() {
        // Regression test for issue #35: repeated password-guessing against a known email had
        // nothing stopping it. The 6th attempt from the same IP within the window should be
        // rejected before even checking credentials.
        when(userRepository.findByEmailHash(any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestFrom("10.0.0.4");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<?> response = controller.login(new AuthRequest("victim@example.com", "guess" + i), request);
            assertEquals(401, response.getStatusCode().value());
        }

        ResponseEntity<?> sixthAttempt = controller.login(new AuthRequest("victim@example.com", "guess5"), request);

        assertEquals(429, sixthAttempt.getStatusCode().value());
        assertEquals("TOO_MANY_REQUESTS", errorBody(sixthAttempt).error());
    }

    @Test
    void login_isNotRateLimited_forADifferentIp() {
        // A different attacker IP (or a different legitimate user entirely) must not be
        // affected by another IP's failed attempts - rate limiting is per-IP, not global.
        when(userRepository.findByEmailHash(any())).thenReturn(Optional.empty());

        for (int i = 0; i < 5; i++) {
            controller.login(new AuthRequest("victim@example.com", "guess" + i), requestFrom("10.0.0.5"));
        }

        ResponseEntity<?> response = controller.login(
                new AuthRequest("victim@example.com", "another-guess"), requestFrom("10.0.0.6"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_succeedingResetsTheFailureCount_forThatIp() {
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        user.setEmailVerified(true);
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));
        MockHttpServletRequest request = requestFrom("10.0.0.7");

        controller.login(new AuthRequest("owner@example.com", "wrong-password"), request);
        controller.login(new AuthRequest("owner@example.com", "wrong-password"), request);
        ResponseEntity<?> successResponse = controller.login(new AuthRequest("owner@example.com", "correct-password"), request);
        assertEquals(200, successResponse.getStatusCode().value());

        // Failure count should be back to zero after the success above - four more wrong
        // attempts (fewer than the limit) should still be plain 401s, not 429.
        for (int i = 0; i < 4; i++) {
            ResponseEntity<?> response = controller.login(new AuthRequest("owner@example.com", "wrong-again"), request);
            assertEquals(401, response.getStatusCode().value());
        }
    }

    @Test
    void login_returns403_whenTheAccountsEmailIsNotVerified() {
        // Regression test for issue #120: correct credentials, but an unverified account -
        // deliberately a distinct status/code from the 401 "wrong email or password" case above,
        // since these credentials genuinely are correct.
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        // emailVerified defaults to false - not set here on purpose.
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.login(
                new AuthRequest("owner@example.com", "correct-password"), requestFrom("10.0.0.8"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals("FORBIDDEN", errorBody(response).error());
    }

    @Test
    void login_deniedForAnUnverifiedAccount_doesNotCountTowardsRateLimiting() {
        // The rate limiter exists to slow down credential-guessing, not to punish a real,
        // correctly-authenticated user for not having verified yet.
        User user = new User();
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("correct-password"));
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));
        MockHttpServletRequest request = requestFrom("10.0.0.9");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<?> response = controller.login(new AuthRequest("owner@example.com", "correct-password"), request);
            assertEquals(403, response.getStatusCode().value());
        }

        ResponseEntity<?> sixthAttempt = controller.login(new AuthRequest("owner@example.com", "correct-password"), request);
        assertEquals(403, sixthAttempt.getStatusCode().value());
    }

    @Test
    void resendVerification_forAnUnverifiedExistingEmail_generatesAndEmailsAFreshToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<Void> response = controller.resendVerification(new ResendVerificationRequest("owner@example.com"));

        assertEquals(200, response.getStatusCode().value());
        verify(emailVerificationTokenRepository).deleteByUserId(1L);
        verify(emailVerificationTokenRepository).save(any());
        verify(authEmailSender).sendVerificationEmail(eq("owner@example.com"), any());
    }

    @Test
    void resendVerification_forAnAlreadyVerifiedEmail_doesNothing() {
        // No-op, not an error - otherwise this endpoint could be used to probe whether a given
        // email is already verified, the same enumeration concern forgotPassword avoids.
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");
        user.setEmailVerified(true);
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<Void> response = controller.resendVerification(new ResendVerificationRequest("owner@example.com"));

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(authEmailSender);
        verify(emailVerificationTokenRepository, never()).save(any());
    }

    @Test
    void resendVerification_forANonExistentEmail_stillReturns200_withoutEmailingAnything() {
        when(userRepository.findByEmailHash(emailHasher.hash("nobody@example.com"))).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.resendVerification(new ResendVerificationRequest("nobody@example.com"));

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(authEmailSender);
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

        ResponseEntity<?> response = controller.logout(requestWithAuthHeader("Bearer " + token));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(tokenBlocklist.isRevoked(token));
    }

    @Test
    void logout_returns400_whenNoAuthorizationHeaderPresent() {
        ResponseEntity<?> response = controller.logout(requestWithAuthHeader(null));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("BAD_REQUEST", errorBody(response).error());
    }

    @Test
    void logout_returns400_whenHeaderDoesNotStartWithBearer() {
        ResponseEntity<?> response = controller.logout(requestWithAuthHeader("Basic somecreds"));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void refresh_issuesANewAccessAndRefreshToken_forAValidRefreshToken() {
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));
        String refreshToken = jwtService.generateRefreshToken("owner@example.com");

        ResponseEntity<?> response = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("owner@example.com", jwtService.extractEmail(authBody(response).token()));
        assertFalse(jwtService.isRefreshToken(authBody(response).token()));
        assertTrue(jwtService.isRefreshToken(authBody(response).refreshToken()));
    }

    @Test
    void refresh_revokesTheOldRefreshToken_soItCannotBeReused() {
        // The actual rotation behaviour (issue #26): a refresh token is single-use. Reusing one
        // that's already been exchanged must fail, the same way a stolen-and-already-used one
        // would for an attacker who got there second.
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));
        String refreshToken = jwtService.generateRefreshToken("owner@example.com");

        controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));
        ResponseEntity<?> secondAttempt = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(401, secondAttempt.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(secondAttempt).error());
    }

    @Test
    void refresh_returns401_whenGivenAnAccessTokenInstead() {
        // The other half of the type-separation check: an access token must not work as a
        // refresh token either, or the distinction between the two is meaningless.
        User user = new User();
        user.setEmail("owner@example.com");
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));
        String accessToken = jwtService.generateAccessToken("owner@example.com");

        ResponseEntity<?> response = controller.refresh(requestWithAuthHeader("Bearer " + accessToken));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refresh_returns401_whenTheAccountNoLongerExists() {
        when(userRepository.findByEmailHash(emailHasher.hash("deleted@example.com"))).thenReturn(Optional.empty());
        String refreshToken = jwtService.generateRefreshToken("deleted@example.com");

        ResponseEntity<?> response = controller.refresh(requestWithAuthHeader("Bearer " + refreshToken));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void refresh_returns400_whenNoAuthorizationHeaderPresent() {
        ResponseEntity<?> response = controller.refresh(requestWithAuthHeader(null));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void forgotPassword_forAnExistingEmail_generatesAndEmailsAToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");
        when(userRepository.findByEmailHash(emailHasher.hash("owner@example.com"))).thenReturn(Optional.of(user));

        ResponseEntity<Void> response = controller.forgotPassword(new ForgotPasswordRequest("owner@example.com"));

        assertEquals(200, response.getStatusCode().value());
        verify(passwordResetTokenRepository).deleteByUserId(1L);
        verify(passwordResetTokenRepository).save(any());
        verify(authEmailSender).sendPasswordResetEmail(eq("owner@example.com"), any());
    }

    @Test
    void forgotPassword_forANonExistentEmail_stillReturns200_withoutEmailingAnything() {
        // Enumeration-avoidance (issue #37, same reasoning as login's identical 401) - the
        // response must not reveal whether the email is actually registered.
        when(userRepository.findByEmailHash(emailHasher.hash("nobody@example.com"))).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.forgotPassword(new ForgotPasswordRequest("nobody@example.com"));

        assertEquals(200, response.getStatusCode().value());
        verifyNoInteractions(authEmailSender);
        verify(passwordResetTokenRepository, never()).save(any());
    }

    @Test
    void resetPassword_withAValidToken_updatesThePasswordAndDeletesTheToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("old-password1"));

        PasswordResetToken token = new PasswordResetToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().plusSeconds(60));

        when(passwordResetTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        // Non-zero: deleteByUserId (issue #115) is now a bulk delete returning the affected-row
        // count, and resetPassword treats 0 as "someone else already consumed this token."
        when(passwordResetTokenRepository.deleteByUserId(1L)).thenReturn(1);

        ResponseEntity<?> response = controller.resetPassword(new ResetPasswordRequest("valid-token", "new-password1"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(passwordEncoder.matches("new-password1", user.getPasswordHash()));
        verify(passwordResetTokenRepository).deleteByUserId(1L);
    }

    @Test
    void resetPassword_returns401_whenTheTokenWasAlreadyConsumedByAConcurrentRequest() {
        // Regression test for issue #115: deleteByUserId returning 0 (its real-world outcome when
        // a near-simultaneous duplicate request already deleted the same row first) must be
        // treated the same as an invalid/expired token, not silently proceed to reset the
        // password anyway.
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");
        user.setPasswordHash(passwordEncoder.encode("old-password1"));

        PasswordResetToken token = new PasswordResetToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().plusSeconds(60));

        when(passwordResetTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordResetTokenRepository.deleteByUserId(1L)).thenReturn(0);

        ResponseEntity<?> response = controller.resetPassword(new ResetPasswordRequest("valid-token", "new-password1"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(response).error());
        verify(userRepository, never()).save(any());
        assertTrue(passwordEncoder.matches("old-password1", user.getPasswordHash()));
    }

    @Test
    void resetPassword_returns401_whenTokenDoesNotExist() {
        when(passwordResetTokenRepository.findByToken("bogus-token")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resetPassword(new ResetPasswordRequest("bogus-token", "new-password1"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(response).error());
    }

    @Test
    void resetPassword_returns401_whenTokenHasExpired() {
        PasswordResetToken expired = new PasswordResetToken();
        expired.setToken("expired-token");
        expired.setUserId(1L);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(passwordResetTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

        ResponseEntity<?> response = controller.resetPassword(new ResetPasswordRequest("expired-token", "new-password1"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void resetPassword_returns400_whenNewPasswordIsTooWeak() {
        PasswordResetToken token = new PasswordResetToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().plusSeconds(60));
        when(passwordResetTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));

        ResponseEntity<?> response = controller.resetPassword(new ResetPasswordRequest("valid-token", "weak"));

        assertEquals(400, response.getStatusCode().value());
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_alsoGeneratesAndEmailsAVerificationToken() {
        when(userRepository.findByEmailHash(emailHasher.hash("new@example.com"))).thenReturn(Optional.empty());

        controller.register(new AuthRequest("new@example.com", "password123"));

        verify(emailVerificationTokenRepository).save(any());
        verify(authEmailSender).sendVerificationEmail(eq("new@example.com"), any());
    }

    @Test
    void verifyEmail_withAValidToken_marksTheUserVerified_andDeletesTheToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");

        EmailVerificationToken token = new EmailVerificationToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().plusSeconds(60));

        when(emailVerificationTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        // Non-zero: same reasoning as resetPassword's equivalent stub above (issue #115).
        when(emailVerificationTokenRepository.deleteByUserId(1L)).thenReturn(1);

        ResponseEntity<?> response = controller.verifyEmail(new VerifyEmailRequest("valid-token"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(user.isEmailVerified());
        verify(emailVerificationTokenRepository).deleteByUserId(1L);
    }

    @Test
    void verifyEmail_returns401_whenTheTokenWasAlreadyConsumedByAConcurrentRequest() {
        // Regression test for issue #115 (the actual bug it was originally filed for): two
        // near-simultaneous requests for the same verification token used to 500 on the loser;
        // deleteByUserId returning 0 must now resolve to the same 401 an already-used token gets.
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");

        EmailVerificationToken token = new EmailVerificationToken();
        token.setToken("valid-token");
        token.setUserId(1L);
        token.setExpiresAt(Instant.now().plusSeconds(60));

        when(emailVerificationTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(emailVerificationTokenRepository.deleteByUserId(1L)).thenReturn(0);

        ResponseEntity<?> response = controller.verifyEmail(new VerifyEmailRequest("valid-token"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(response).error());
        verify(userRepository, never()).save(any());
        assertFalse(user.isEmailVerified());
    }

    @Test
    void verifyEmail_returns401_whenTokenDoesNotExist() {
        when(emailVerificationTokenRepository.findByToken("bogus-token")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.verifyEmail(new VerifyEmailRequest("bogus-token"));

        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", errorBody(response).error());
    }

    @Test
    void verifyEmail_returns401_whenTokenHasExpired() {
        EmailVerificationToken expired = new EmailVerificationToken();
        expired.setToken("expired-token");
        expired.setUserId(1L);
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(emailVerificationTokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

        ResponseEntity<?> response = controller.verifyEmail(new VerifyEmailRequest("expired-token"));

        assertEquals(401, response.getStatusCode().value());
    }
}
