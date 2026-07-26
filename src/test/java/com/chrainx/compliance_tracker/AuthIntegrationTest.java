package com.chrainx.compliance_tracker;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void unauthenticatedRequest_toBusinesses_isRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/businesses", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void registerThenUseToken_canCreateAndListOwnBusiness() {
        String email = "auth-e2e-" + System.nanoTime() + "@example.com";

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password"), AuthResponse.class);
        assertEquals(HttpStatus.OK, registerResponse.getStatusCode());
        String token = registerResponse.getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        Business newBusiness = new Business();
        newBusiness.setName("E2E Auth Test Co");
        newBusiness.setFinancialYearEnd(java.time.LocalDate.of(2026, 12, 31));
        newBusiness.setGstRegistered(false);

        ResponseEntity<Business> createResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(newBusiness, headers), Business.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());

        ResponseEntity<Business[]> listResponse = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), Business[].class);

        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertTrue(listResponse.getBody().length >= 1);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.getName().equals("E2E Auth Test Co")));
    }

    @Test
    void createBusiness_withAnotherUsersExistingId_doesNotOverwriteTheirBusiness() {
        // Full-stack regression test for issue #66: user A creates a business, then user B
        // (a completely separate account) tries to "create" a business while supplying A's
        // real business id in the request body. Before the fix, JPA's save() treated the
        // non-null id as an UPDATE, silently handing B ownership of A's business. After the
        // fix, the id is stripped server-side, so B gets a brand-new business and A's is
        // untouched.
        String emailA = "auth-e2e-idor-a-" + System.nanoTime() + "@example.com";
        String emailB = "auth-e2e-idor-b-" + System.nanoTime() + "@example.com";

        String tokenA = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(emailA, "a-real-password"), AuthResponse.class)
                .getBody().token();
        String tokenB = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(emailB, "a-real-password"), AuthResponse.class)
                .getBody().token();

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);

        Business businessA = new Business();
        businessA.setName("User A's Real Business");
        businessA.setFinancialYearEnd(java.time.LocalDate.of(2026, 12, 31));
        businessA.setGstRegistered(false);

        ResponseEntity<Business> createAResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(businessA, headersA), Business.class);
        Long businessAId = createAResponse.getBody().getId();

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(tokenB);

        Business attackerPayload = new Business();
        attackerPayload.setId(businessAId); // the actual exploit: reusing A's real id
        attackerPayload.setName("Hijacked by User B");
        attackerPayload.setFinancialYearEnd(java.time.LocalDate.of(2026, 12, 31));
        attackerPayload.setGstRegistered(false);

        ResponseEntity<Business> attackResponse = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(attackerPayload, headersB), Business.class);

        assertEquals(HttpStatus.OK, attackResponse.getStatusCode());
        assertTrue(attackResponse.getBody().getId() != businessAId,
                "attacker's business must get a fresh id, not overwrite A's");

        ResponseEntity<Business[]> listAResponse = restTemplate.exchange(
                "/api/businesses", org.springframework.http.HttpMethod.GET, new HttpEntity<>(headersA), Business[].class);

        assertTrue(java.util.Arrays.stream(listAResponse.getBody())
                .anyMatch(b -> b.getId().equals(businessAId) && b.getName().equals("User A's Real Business")),
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
                "/api/auth/register", new AuthRequest(email, "a-real-password"), AuthResponse.class)
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
                "/api/auth/register", new AuthRequest(email, "a-real-password"), AuthResponse.class)
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
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password"), AuthResponse.class);

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
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password"), AuthResponse.class);

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "wrong-password"), AuthResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginResponse.getStatusCode());
    }
}
