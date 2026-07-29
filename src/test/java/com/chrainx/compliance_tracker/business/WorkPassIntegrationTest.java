package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.AuthResponse;
import com.chrainx.compliance_tracker.auth.AuthRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as AuthIntegrationTest: WorkPassControllerTest
// proves the Java logic against mocked repositories, but only a real HTTP call through the real
// security filter chain and real Postgres can prove that a business a user doesn't own actually
// stays inaccessible end to end (issue #24).
//
// Uses WorkPassRequest/WorkPassResponse throughout (issue #46), not the WorkPass entity.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class WorkPassIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String registerAndGetToken() {
        String email = "workpass-e2e-" + System.nanoTime() + "@example.com";
        return restTemplate.postForEntity(
                        "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private Long createBusiness(HttpHeaders headers) {
        BusinessRequest request = new BusinessRequest("WorkPass E2E Test Co", LocalDate.of(2026, 12, 31), false, null, null);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
    }

    // GET .../work-passes returns a PageResponse<WorkPassResponse>, not a bare array (issue
    // #49) - ParameterizedTypeReference needed to deserialize the generic type correctly.
    private List<WorkPassResponse> listWorkPasses(Long businessId, HttpHeaders headers) {
        ResponseEntity<PageResponse<WorkPassResponse>> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/work-passes", HttpMethod.GET, new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<PageResponse<WorkPassResponse>>() {});
        return response.getBody().content();
    }

    @Test
    void createThenListWorkPass_forOwnBusiness_succeeds() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        WorkPassRequest pass = new WorkPassRequest("Jane Doe", LocalDate.of(2026, 11, 1));

        ResponseEntity<WorkPassResponse> createResponse = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), WorkPassResponse.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        assertEquals("Jane Doe", createResponse.getBody().employeeName());

        assertTrue(listWorkPasses(businessId, headers).stream()
                .anyMatch(p -> p.employeeName().equals("Jane Doe")));
    }

    @Test
    void createWorkPass_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA);

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        WorkPassRequest pass = new WorkPassRequest("Should Not Be Created", LocalDate.of(2026, 11, 1));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessAId + "/work-passes", new HttpEntity<>(pass, headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void deleteWorkPass_removesIt_soItNoLongerAppearsInList() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        WorkPassRequest pass = new WorkPassRequest("To Be Deleted", LocalDate.of(2026, 11, 1));

        Long passId = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), WorkPassResponse.class)
                .getBody().id();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId + "/work-passes/" + passId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        assertTrue(listWorkPasses(businessId, headers).stream()
                .noneMatch(p -> p.id().equals(passId)));
    }

    @Test
    void deleteWorkPass_belongingToAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA);

        WorkPassRequest pass = new WorkPassRequest("Owned By A", LocalDate.of(2026, 11, 1));

        Long passId = restTemplate.postForEntity(
                "/api/businesses/" + businessAId + "/work-passes", new HttpEntity<>(pass, headersA), WorkPassResponse.class)
                .getBody().id();

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessAId + "/work-passes/" + passId, HttpMethod.DELETE,
                new HttpEntity<>(headersB), Void.class);

        assertEquals(HttpStatus.NOT_FOUND, deleteResponse.getStatusCode());
    }

    // Real HTTP, deliberately - @Valid only runs during Spring MVC's request-body binding, which
    // calling the controller method directly (as WorkPassControllerTest does) never exercises.
    @Test
    void createWorkPass_withBlankEmployeeName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        WorkPassRequest pass = new WorkPassRequest("", LocalDate.of(2026, 11, 1));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createWorkPass_withNullExpiryDate_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        WorkPassRequest pass = new WorkPassRequest("Jane Doe", null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
