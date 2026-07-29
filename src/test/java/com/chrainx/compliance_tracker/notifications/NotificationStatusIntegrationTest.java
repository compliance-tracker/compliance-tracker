package com.chrainx.compliance_tracker.notifications;

import com.chrainx.compliance_tracker.auth.AuthRequest;
import com.chrainx.compliance_tracker.auth.AuthResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

// Real, unmocked, full-stack test - same reasoning as OpenApiIntegrationTest/
// ActuatorHealthIntegrationTest: only a real HTTP call through the actual security filter chain
// proves this new endpoint (issue #114) actually requires auth, and actually reflects this test
// environment's real notifications.channel=logging default (application-test.properties doesn't
// override it), not just that the controller/DTO compile.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class NotificationStatusIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void status_requiresAuth_likeEveryOtherRealApiEndpoint() {
        // Unlike /actuator/health or the OpenAPI docs, this reflects real app configuration
        // (even if not per-user today) - it belongs behind the same default
        // .anyRequest().authenticated() as the rest of the API, not permitAll'd.
        ResponseEntity<String> response = restTemplate.getForEntity("/api/notifications/status", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void status_forAnAuthenticatedCaller_reportsTheRealConfiguredChannel() {
        String email = "notif-status-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class);
        String token = registerResponse.getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<NotificationStatusResponse> response = restTemplate.exchange(
                "/api/notifications/status", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), NotificationStatusResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // This test suite never sets notifications.channel=email (see application-test.properties/
        // application.properties defaults), so the real config genuinely resolves to logging here.
        assertEquals("logging", response.getBody().channel());
    }

    @Test
    void status_forTheLoggingChannel_omitsFromAddressFromTheRealJsonBody() {
        // Checked against the raw JSON, not the deserialized record - @JsonInclude(NON_NULL)
        // omitting the field entirely (vs. serializing it as null) is only actually proven by
        // looking at the real response body.
        String email = "notif-status-omit-" + System.nanoTime() + "@example.com";
        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class);
        String token = registerResponse.getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/notifications/status", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertFalse(response.getBody().contains("fromAddress"));
    }
}
