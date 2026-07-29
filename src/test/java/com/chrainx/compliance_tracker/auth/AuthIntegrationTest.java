package com.chrainx.compliance_tracker.auth;
import com.chrainx.compliance_tracker.error.ApiError;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test: boots the actual app on a real port and makes real HTTP
// calls through TestRestTemplate - unlike BusinessControllerTest (which calls controller
// methods directly in Java), this is the only test that actually exercises SecurityConfig's
// filter chain. A bug in the security rules themselves (e.g. a wrong path pattern, a missing
// permitAll) wouldn't show up in the method-level tests at all - only here.
//
// @TestMethodOrder: every TestRestTemplate call in this class shares one real HTTP client
// hitting the same loopback address, and LoginRateLimiter is a singleton bean keyed by that
// same IP across the whole (cached) Spring context - so the rate-limit test below is @Order(1)
// to guarantee it runs before any other test's failed login attempt (e.g.
// login_withWrongPassword_isRejected) can pollute its count.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    // Autowired directly to read the reset token's real value after POST /api/auth/forgot-password
    // - the token is only ever emailed (logged, not sent, in this test's default config), never
    // returned in the HTTP response itself, so there's no other way to get it for the next step.
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    // Same reasoning, for the email verification token issued on register (issue #36).
    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void unauthenticatedRequest_toBusinesses_isRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/businesses", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void unauthenticatedRequest_stillGetsCorsHeaders_onItsRejection() {
        // Regression test for issue #83: CORS used to be MVC-level only
        // (WebMvcConfigurer.addCorsMappings), which never ran on a request Spring Security's own
        // filter chain rejected early - so a 401 had no Access-Control-Allow-Origin header at
        // all, invisible to browser JS as anything more specific than an opaque network failure.
        // A method-level test (mocked, no real filter chain) couldn't catch this - only a real
        // HTTP call through the actual SecurityConfig chain, exactly like this one, can.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Origin", "http://localhost:5173");
        headers.set("Authorization", "Bearer garbage-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("http://localhost:5173", response.getHeaders().getFirst("Access-Control-Allow-Origin"));
    }

    @Test
    void registerThenUseToken_canCreateAndListOwnBusiness() {
        String email = "auth-e2e-" + System.nanoTime() + "@example.com";

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());
        String token = registerResponse.getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        com.chrainx.compliance_tracker.business.BusinessRequest newBusiness =
                new com.chrainx.compliance_tracker.business.BusinessRequest(
                        "E2E Auth Test Co", java.time.LocalDate.of(2026, 12, 31), false, null);

        ResponseEntity<com.chrainx.compliance_tracker.business.BusinessResponse> createResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(newBusiness, headers),
                com.chrainx.compliance_tracker.business.BusinessResponse.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());

        // GET /api/businesses returns a PageResponse<BusinessResponse>, not a bare array (issue
        // #49) - ParameterizedTypeReference needed to deserialize the generic type correctly.
        ResponseEntity<com.chrainx.compliance_tracker.business.PageResponse<com.chrainx.compliance_tracker.business.BusinessResponse>> listResponse =
                restTemplate.exchange("/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers),
                        new org.springframework.core.ParameterizedTypeReference<
                                com.chrainx.compliance_tracker.business.PageResponse<com.chrainx.compliance_tracker.business.BusinessResponse>>() {});

        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertTrue(listResponse.getBody().content().size() >= 1);
        assertTrue(listResponse.getBody().content().stream()
                .anyMatch(b -> b.name().equals("E2E Auth Test Co")));
    }

    @Test
    void createBusiness_withAnotherUsersExistingId_doesNotOverwriteTheirBusiness() {
        // Full-stack regression test for issue #66: user A creates a business, then user B
        // (a completely separate account) tries to "create" a business while supplying A's
        // real business id in the request body. Before the fix, JPA's save() treated the
        // non-null id as an UPDATE, silently handing B ownership of A's business.
        //
        // BusinessRequest (issue #46) has no id field at all, so a real Java client literally
        // can't express this attack anymore through the typed DTO - but a real attacker isn't
        // bound by our Java types either, they can send whatever raw JSON they want. This test
        // sends raw JSON with an "id" field BusinessRequest doesn't declare, the realistic shape
        // of the actual attack, and confirms it's simply ignored as an unrecognized property.
        String emailA = "auth-e2e-idor-a-" + System.nanoTime() + "@example.com";
        String emailB = "auth-e2e-idor-b-" + System.nanoTime() + "@example.com";

        String tokenA = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(emailA, "a-real-password1"), AuthResponse.class)
                .getBody().token();
        String tokenB = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(emailB, "a-real-password1"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);

        com.chrainx.compliance_tracker.business.BusinessRequest businessA =
                new com.chrainx.compliance_tracker.business.BusinessRequest(
                        "User A's Real Business", java.time.LocalDate.of(2026, 12, 31), false, null);

        ResponseEntity<com.chrainx.compliance_tracker.business.BusinessResponse> createAResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(businessA, headersA),
                com.chrainx.compliance_tracker.business.BusinessResponse.class);
        Long businessAId = createAResponse.getBody().id();

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(tokenB);
        headersB.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        String attackerPayload = """
                {"id": %d, "name": "Hijacked by User B", "financialYearEnd": "2026-12-31", "gstRegistered": false}
                """.formatted(businessAId);

        ResponseEntity<com.chrainx.compliance_tracker.business.BusinessResponse> attackResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(attackerPayload, headersB),
                com.chrainx.compliance_tracker.business.BusinessResponse.class);

        assertEquals(HttpStatus.OK, attackResponse.getStatusCode());
        assertTrue(!attackResponse.getBody().id().equals(businessAId),
                "attacker's business must get a fresh id, not overwrite A's");

        ResponseEntity<com.chrainx.compliance_tracker.business.PageResponse<com.chrainx.compliance_tracker.business.BusinessResponse>> listAResponse =
                restTemplate.exchange("/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headersA),
                        new org.springframework.core.ParameterizedTypeReference<
                                com.chrainx.compliance_tracker.business.PageResponse<com.chrainx.compliance_tracker.business.BusinessResponse>>() {});

        assertTrue(listAResponse.getBody().content().stream()
                .anyMatch(b -> b.id().equals(businessAId) && b.name().equals("User A's Real Business")),
                "User A's original business must still exist, unmodified, still owned by A");
    }

    @Test
    void malformedIdWithValidToken_returns400NotUnauthorized() {
        // Regression test for issue #67: a valid token hitting a malformed path parameter
        // (a non-numeric id where a Long is expected) should fail validation with 400, not
        // be misreported as an auth failure. Before the fix, Spring MVC's internal forward to
        // /error (triggered by the MethodArgumentTypeMismatchException) was itself blocked by
        // SecurityConfig's anyRequest().authenticated() rule, so it never reached the real
        // 400 response - it fell through to the 401 AuthenticationEntryPoint instead.
        String email = "auth-e2e-malformed-id-" + System.nanoTime() + "@example.com";
        String token = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/not-a-number/deadlines", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void unmappedPathWithValidToken_returns404NotUnauthorized() {
        // Same root cause as above (issue #67), different trigger: a truly unmapped path
        // (NoHandlerFoundException) should 404, not be misreported as 401.
        String email = "auth-e2e-unmapped-path-" + System.nanoTime() + "@example.com";
        String token = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/completely-made-up", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @Order(1) // must run before any other test's failed login attempt - see class-level comment
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void repeatedFailedLogins_fromSameIp_getRateLimitedWith429() {
        String email = "auth-e2e-ratelimit-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password1"), AuthResponse.class);

        for (int i = 0; i < 5; i++) {
            ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                    "/api/auth/login", new AuthRequest(email, "wrong-password"), AuthResponse.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }

        ResponseEntity<AuthResponse> sixthAttempt = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "wrong-password"), AuthResponse.class);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, sixthAttempt.getStatusCode());
    }

    @Test
    void login_withWrongPassword_isRejected() {
        String email = "auth-e2e-wrongpass-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password1"), AuthResponse.class);

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "wrong-password"), AuthResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginResponse.getStatusCode());
    }

    @Test
    void login_withWrongPassword_returnsAConsistentStructuredErrorBody() {
        // Regression test for issue #47: proves the actual JSON shape a real client receives,
        // not just the status code - a unit test calling the controller method directly never
        // serializes through Jackson, so it can't catch a body shape that doesn't match what
        // ResponseEntity<?> + ApiError was supposed to produce.
        String email = "auth-e2e-error-shape-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password1"), AuthResponse.class);

        ResponseEntity<com.chrainx.compliance_tracker.error.ApiError> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "wrong-password"),
                ApiError.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginResponse.getStatusCode());
        assertEquals("UNAUTHORIZED", loginResponse.getBody().error());
        assertEquals("Incorrect email or password.", loginResponse.getBody().message());
    }

    @Test
    void concurrentRegistrations_forTheSameEmail_resolveToExactlyOneSuccess() throws Exception {
        // Regression test for issue #42: two real threads firing the same registration request
        // at genuinely the same instant, against real Postgres - not simulated via mocks. A
        // CountDownLatch holds both threads at the starting line so they hit
        // userRepository.findByEmail() as close to simultaneously as the JVM allows, maximizing
        // the chance of actually reproducing the race window (both see "no such email yet"
        // before either commits) rather than one just happening to finish first every time.
        String email = "auth-e2e-race-" + System.nanoTime() + "@example.com";
        AuthRequest request = new AuthRequest(email, "a-real-password1");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);

        List<Future<ResponseEntity<AuthResponse>>> futures = List.of(
                executor.submit(() -> {
                    startLine.await();
                    return restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
                }),
                executor.submit(() -> {
                    startLine.await();
                    return restTemplate.postForEntity("/api/auth/register", request, AuthResponse.class);
                })
        );

        startLine.countDown();
        List<Integer> statusCodes = futures.stream()
                .map(f -> {
                    try {
                        return f.get(10, TimeUnit.SECONDS).getStatusCode().value();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());
        executor.shutdown();

        // Exactly one request should have won (200) and the other should see the clean 409 -
        // never two 200s (would mean two accounts for one email, or a corrupted response), and
        // critically never a 500 (would mean the race actually reached the unhandled-exception
        // path this fix closes).
        assertEquals(1, statusCodes.stream().filter(s -> s == 200).count());
        assertEquals(1, statusCodes.stream().filter(s -> s == 409).count());
    }

    @Test
    void loggedOutToken_isRejected_onTheVeryNextRequest() {
        // Regression test for issue #41: a JWT normally stays valid until it naturally expires,
        // even after "logout" - this proves the token this test just used to log in genuinely
        // stops working the moment /api/auth/logout is called for it, not just that the
        // endpoint returns 200.
        String email = "auth-e2e-logout-" + System.nanoTime() + "@example.com";
        String token = restTemplate.postForEntity(
                        "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> beforeLogout = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, beforeLogout.getStatusCode());

        ResponseEntity<Void> logoutResponse = restTemplate.exchange(
                "/api/auth/logout", org.springframework.http.HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.OK, logoutResponse.getStatusCode());

        ResponseEntity<String> afterLogout = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, afterLogout.getStatusCode());
    }

    @Test
    void logout_withNoAuthorizationHeader_returns400() {
        ResponseEntity<Void> response = restTemplate.postForEntity("/api/auth/logout", null, Void.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void refresh_issuesAWorkingNewAccessToken_andRotatesTheRefreshToken() {
        // Regression test for issue #26, real full-stack proof: the new access token this test
        // gets back from /api/auth/refresh must actually work against a real protected
        // endpoint, and the old refresh token it was exchanged for must be genuinely dead
        // afterward - not just that the endpoint returned 200.
        String email = "auth-e2e-refresh-" + System.nanoTime() + "@example.com";
        AuthResponse original = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class).getBody();

        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.setBearerAuth(original.refreshToken());

        ResponseEntity<AuthResponse> refreshResponse = restTemplate.postForEntity(
                "/api/auth/refresh", new HttpEntity<>(refreshHeaders), AuthResponse.class);
        assertEquals(HttpStatus.OK, refreshResponse.getStatusCode());
        AuthResponse refreshed = refreshResponse.getBody();

        HttpHeaders newAccessHeaders = new HttpHeaders();
        newAccessHeaders.setBearerAuth(refreshed.token());
        ResponseEntity<String> businessesResponse = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(newAccessHeaders), String.class);
        assertEquals(HttpStatus.OK, businessesResponse.getStatusCode());

        // Reusing the original (now-rotated) refresh token must fail.
        ResponseEntity<AuthResponse> reuseAttempt = restTemplate.postForEntity(
                "/api/auth/refresh", new HttpEntity<>(refreshHeaders), AuthResponse.class);
        assertEquals(HttpStatus.UNAUTHORIZED, reuseAttempt.getStatusCode());
    }

    @Test
    void refreshToken_cannotBeUsedAsAnAccessToken() {
        String email = "auth-e2e-refresh-as-access-" + System.nanoTime() + "@example.com";
        String refreshToken = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().refreshToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void accessToken_cannotBeUsedToRefresh() {
        String email = "auth-e2e-access-as-refresh-" + System.nanoTime() + "@example.com";
        String accessToken = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/refresh", new HttpEntity<>(headers), AuthResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void fullPasswordResetFlow_letsTheUserLogInWithTheNewPasswordOnly() {
        // Real HTTP, end to end (issue #37): register -> forgot-password -> read the real token
        // out of the DB (the only way to get it, since it's only ever emailed) -> reset-password
        // -> old password now fails, new password works.
        String email = "auth-e2e-reset-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "old-password1"), AuthResponse.class);

        ResponseEntity<Void> forgotResponse = restTemplate.postForEntity(
                "/api/auth/forgot-password", new ForgotPasswordRequest(email), Void.class);
        assertEquals(HttpStatus.OK, forgotResponse.getStatusCode());

        String realToken = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .reduce((first, second) -> second) // most recently inserted
                .orElseThrow()
                .getToken();

        ResponseEntity<Void> resetResponse = restTemplate.postForEntity(
                "/api/auth/reset-password", new ResetPasswordRequest(realToken, "new-password1"), Void.class);
        assertEquals(HttpStatus.OK, resetResponse.getStatusCode());

        ResponseEntity<AuthResponse> oldPasswordAttempt = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "old-password1"), AuthResponse.class);
        assertEquals(HttpStatus.UNAUTHORIZED, oldPasswordAttempt.getStatusCode());

        ResponseEntity<AuthResponse> newPasswordAttempt = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "new-password1"), AuthResponse.class);
        assertEquals(HttpStatus.OK, newPasswordAttempt.getStatusCode());
    }

    @Test
    void resetPassword_invalidatesTokensIssuedBeforeTheReset_butNotAFreshLoginAfter() throws InterruptedException {
        // Real, end-to-end proof of issue #96: a password reset must not just stop the old
        // password from working (already covered above) - any access/refresh token minted
        // *before* the reset needs to stop working too, since a reset is often prompted by "I
        // think someone else has my password", and a token they already obtained shouldn't
        // outlive that.
        String email = "auth-e2e-reset-sessions-" + System.nanoTime() + "@example.com";
        AuthResponse original = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "old-password1"), AuthResponse.class).getBody();

        // A JWT's issued-at only has second precision, and tokenValidAfter's own floor to the
        // second (see JwtAuthenticationFilter.isValidForUser's comment) means a token minted in
        // the *same second* as the reset is deliberately still accepted - an inherent trade-off
        // of that precision, not a bug. Sleeping past the second boundary here is what makes this
        // test actually prove the invalidation, rather than risk a coin-flip pass depending on
        // how fast the two HTTP calls happen to land relative to a wall-clock second tick.
        Thread.sleep(1100);

        restTemplate.postForEntity("/api/auth/forgot-password", new ForgotPasswordRequest(email), Void.class);
        String realToken = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getToken();
        restTemplate.postForEntity(
                "/api/auth/reset-password", new ResetPasswordRequest(realToken, "new-password1"), Void.class);

        // The pre-reset access token no longer authenticates anything...
        HttpHeaders oldAccessHeaders = new HttpHeaders();
        oldAccessHeaders.setBearerAuth(original.token());
        ResponseEntity<String> businessesWithOldToken = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(oldAccessHeaders), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, businessesWithOldToken.getStatusCode());

        // ...and the pre-reset refresh token can't be used to mint a new one either, or the
        // whole point of the invalidation would be trivially bypassable.
        HttpHeaders oldRefreshHeaders = new HttpHeaders();
        oldRefreshHeaders.setBearerAuth(original.refreshToken());
        ResponseEntity<AuthResponse> refreshWithOldToken = restTemplate.postForEntity(
                "/api/auth/refresh", new HttpEntity<>(oldRefreshHeaders), AuthResponse.class);
        assertEquals(HttpStatus.UNAUTHORIZED, refreshWithOldToken.getStatusCode());

        // But a fresh login with the new password issues tokens that work normally - the
        // invalidation is a floor on issued-at, not a permanent lockout.
        AuthResponse freshLogin = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "new-password1"), AuthResponse.class).getBody();
        HttpHeaders newAccessHeaders = new HttpHeaders();
        newAccessHeaders.setBearerAuth(freshLogin.token());
        ResponseEntity<String> businessesWithNewToken = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(newAccessHeaders), String.class);
        assertEquals(HttpStatus.OK, businessesWithNewToken.getStatusCode());
    }

    @Test
    void resetPassword_withTheSameTokenTwice_failsTheSecondTime() {
        // The actual single-use guarantee, over real HTTP - a token that's already been
        // consumed (or intercepted and reused by someone else after the legitimate reset)
        // must not work again.
        String email = "auth-e2e-reset-reuse-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "old-password1"), AuthResponse.class);
        restTemplate.postForEntity(
                "/api/auth/forgot-password", new ForgotPasswordRequest(email), Void.class);

        String realToken = passwordResetTokenRepository.findAll().stream()
                .filter(t -> t.getUserId() != null)
                .reduce((first, second) -> second)
                .orElseThrow()
                .getToken();

        restTemplate.postForEntity(
                "/api/auth/reset-password", new ResetPasswordRequest(realToken, "new-password1"), Void.class);
        ResponseEntity<ApiError> secondAttempt = restTemplate.postForEntity(
                "/api/auth/reset-password", new ResetPasswordRequest(realToken, "another-password1"),
                ApiError.class);

        assertEquals(HttpStatus.UNAUTHORIZED, secondAttempt.getStatusCode());
    }

    @Test
    void forgotPassword_forANonExistentEmail_stillReturns200() {
        // Enumeration-avoidance, proven over real HTTP - the endpoint must behave identically
        // whether the email exists or not.
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/auth/forgot-password",
                new ForgotPasswordRequest("nobody-" + System.nanoTime() + "@example.com"),
                Void.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void fullEmailVerificationFlow_marksTheAccountVerified() {
        // Real HTTP, end to end (issue #36): register -> account starts unverified -> read the
        // real token out of the DB (only ever emailed, same technique as the password reset
        // flow tests) -> verify-email -> account is now verified, token is gone.
        String email = "auth-e2e-verify-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class);

        User freshlyRegistered = userRepository.findByEmail(email).orElseThrow();
        assertFalse(freshlyRegistered.isEmailVerified());

        String realToken = emailVerificationTokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(freshlyRegistered.getId()))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getToken();

        ResponseEntity<Void> verifyResponse = restTemplate.postForEntity(
                "/api/auth/verify-email", new VerifyEmailRequest(realToken), Void.class);
        assertEquals(HttpStatus.OK, verifyResponse.getStatusCode());

        User verified = userRepository.findByEmail(email).orElseThrow();
        assertTrue(verified.isEmailVerified());
        assertTrue(emailVerificationTokenRepository.findByToken(realToken).isEmpty());
    }

    @Test
    void verifyEmail_withTheSameTokenTwice_failsTheSecondTime() {
        String email = "auth-e2e-verify-reuse-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class);

        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        String realToken = emailVerificationTokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getToken();

        restTemplate.postForEntity("/api/auth/verify-email", new VerifyEmailRequest(realToken), Void.class);
        ResponseEntity<ApiError> secondAttempt = restTemplate.postForEntity(
                "/api/auth/verify-email", new VerifyEmailRequest(realToken), ApiError.class);

        assertEquals(HttpStatus.UNAUTHORIZED, secondAttempt.getStatusCode());
    }

    @Test
    void verifyEmail_returns401_forABogusToken() {
        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/auth/verify-email", new VerifyEmailRequest("this-token-was-never-issued"), ApiError.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("UNAUTHORIZED", response.getBody().error());
    }
}
