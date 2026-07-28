package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.AuthResponse;
import com.chrainx.compliance_tracker.auth.AuthRequest;
import com.chrainx.compliance_tracker.error.ApiError;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as WorkPassIntegrationTest: proves the
// ownership scoping actually holds through the real security filter chain and real Postgres,
// and (for delete) that the V3 migration's ON DELETE CASCADE genuinely works, not just that the
// application code compiles against a schema that happens to match in theory.
//
// Uses BusinessRequest/BusinessResponse throughout (issue #46), not the Business entity - the
// point of this test suite is proving what a real HTTP client actually sends/receives, which is
// the DTOs, not whatever shape the entity happens to be internally.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class BusinessIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeadlineSyncService deadlineSyncService;

    private String registerAndGetToken() {
        String email = "business-e2e-" + System.nanoTime() + "@example.com";
        return restTemplate.postForEntity(
                        "/api/auth/register", new AuthRequest(email, "a-real-password1"), AuthResponse.class)
                .getBody().token();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private Long createBusiness(HttpHeaders headers, String name) {
        BusinessRequest request = new BusinessRequest(name, LocalDate.of(2026, 12, 31), false);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
    }

    @Test
    void updateBusiness_appliesChanges_forOwnBusiness() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        BusinessRequest updates = new BusinessRequest("Corrected Name", LocalDate.of(2027, 3, 31), true);

        ResponseEntity<BusinessResponse> updateResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.PUT, new HttpEntity<>(updates, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals("Corrected Name", updateResponse.getBody().name());
        assertEquals(LocalDate.of(2027, 3, 31), updateResponse.getBody().financialYearEnd());
        assertTrue(updateResponse.getBody().gstRegistered());

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), BusinessResponse[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.id().equals(businessId) && b.name().equals("Corrected Name")));
    }

    @Test
    void updateBusiness_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "User A's Business");

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        BusinessRequest updates = new BusinessRequest("Hijacked Name", LocalDate.of(2027, 3, 31), true);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessAId, HttpMethod.PUT, new HttpEntity<>(updates, headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headersA), BusinessResponse[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.id().equals(businessAId) && b.name().equals("User A's Business")));
    }

    @Test
    void deleteBusiness_removesIt_soItNoLongerAppearsInList() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "To Be Deleted");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), BusinessResponse[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .noneMatch(b -> b.id().equals(businessId)));
    }

    @Test
    void deleteBusiness_belongingToAnotherUser_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "User A's Business");

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessAId, HttpMethod.DELETE, new HttpEntity<>(headersB), Void.class);
        assertEquals(HttpStatus.NOT_FOUND, deleteResponse.getStatusCode());

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headersA), BusinessResponse[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.id().equals(businessAId)));
    }

    @Test
    void deleteBusiness_cascadesToItsWorkPassesAndDeadlineRecords() {
        // Regression test for the V3 migration: before it, business/work_pass/deadline_record
        // had plain (non-cascading) foreign keys, so deleting a business with either a work
        // pass or a synced deadline record would fail outright with a foreign key violation
        // (surfacing as an unhandled 500, since deleteBusiness has no special handling for it).
        // This creates real rows of both kinds, then proves the delete still succeeds cleanly.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Business With Dependents");

        WorkPassRequest pass = new WorkPassRequest("Jane Doe", LocalDate.of(2026, 11, 1));
        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), WorkPassResponse.class);

        // Real DeadlineRecord rows, not just a computed-on-the-fly rules.Deadline - calling the
        // actual scheduled sync logic directly (same method @Scheduled would call) rather than
        // waiting for or mocking the schedule.
        deadlineSyncService.syncDeadlines();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());
    }

    // The tests below are real HTTP calls deliberately - @Valid is enforced by Spring MVC's
    // argument resolution when the DispatcherServlet actually binds the request body, which
    // calling a controller method directly in Java (as BusinessControllerTest does) never
    // triggers at all. Only a real HTTP round trip can prove issue #20's validation works.

    @Test
    void createBusiness_withBlankName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("", LocalDate.of(2026, 12, 31), false);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createBusiness_withBlankName_returnsAConsistentStructuredErrorBody() {
        // Regression test for issue #47: a failed @Valid check is thrown as
        // MethodArgumentNotValidException during Spring MVC's own argument binding, handled by
        // GlobalExceptionHandler, not by BusinessController itself - this proves that global
        // handler produces the same ApiError shape as every controller's own deliberate error
        // responses, not Spring Boot's default (much more verbose) validation error body.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("", LocalDate.of(2026, 12, 31), false);

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), ApiError.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().error());
        assertTrue(response.getBody().message().contains("name"));
    }

    @Test
    void createBusiness_withNullFinancialYearEnd_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Valid Name", null, false);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateBusiness_withBlankName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        BusinessRequest updates = new BusinessRequest("", LocalDate.of(2027, 3, 31), false);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.PUT, new HttpEntity<>(updates, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
