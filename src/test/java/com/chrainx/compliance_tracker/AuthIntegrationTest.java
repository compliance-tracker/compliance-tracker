package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test: boots the actual app on a real port and makes real HTTP
// calls through TestRestTemplate - unlike BusinessControllerTest (which calls controller
// methods directly in Java), this is the only test that actually exercises SecurityConfig's
// filter chain. A bug in the security rules themselves (e.g. a wrong path pattern, a missing
// permitAll) wouldn't show up in the method-level tests at all - only here.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
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
    void login_withWrongPassword_isRejected() {
        String email = "auth-e2e-wrongpass-" + System.nanoTime() + "@example.com";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, "correct-password"), AuthResponse.class);

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login", new AuthRequest(email, "wrong-password"), AuthResponse.class);

        assertEquals(HttpStatus.UNAUTHORIZED, loginResponse.getStatusCode());
    }
}
