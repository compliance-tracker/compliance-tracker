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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as WorkPassIntegrationTest: proves the
// ownership scoping actually holds through the real security filter chain and real Postgres,
// and (for delete) that the V3 migration's ON DELETE CASCADE genuinely works, not just that the
// application code compiles against a schema that happens to match in theory.
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
        Business business = new Business();
        business.setName(name);
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(business, headers), Business.class)
                .getBody().getId();
    }

    @Test
    void updateBusiness_appliesChanges_forOwnBusiness() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        Business updates = new Business();
        updates.setName("Corrected Name");
        updates.setFinancialYearEnd(LocalDate.of(2027, 3, 31));
        updates.setGstRegistered(true);

        ResponseEntity<Business> updateResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.PUT, new HttpEntity<>(updates, headers), Business.class);

        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals("Corrected Name", updateResponse.getBody().getName());
        assertEquals(LocalDate.of(2027, 3, 31), updateResponse.getBody().getFinancialYearEnd());
        assertTrue(updateResponse.getBody().isGstRegistered());

        ResponseEntity<Business[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), Business[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.getId().equals(businessId) && b.getName().equals("Corrected Name")));
    }

    @Test
    void updateBusiness_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "User A's Business");

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        Business updates = new Business();
        updates.setName("Hijacked Name");
        updates.setFinancialYearEnd(LocalDate.of(2027, 3, 31));
        updates.setGstRegistered(true);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessAId, HttpMethod.PUT, new HttpEntity<>(updates, headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        ResponseEntity<Business[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headersA), Business[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.getId().equals(businessAId) && b.getName().equals("User A's Business")));
    }

    @Test
    void deleteBusiness_removesIt_soItNoLongerAppearsInList() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "To Be Deleted");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        ResponseEntity<Business[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), Business[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .noneMatch(b -> b.getId().equals(businessId)));
    }

    @Test
    void deleteBusiness_belongingToAnotherUser_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "User A's Business");

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessAId, HttpMethod.DELETE, new HttpEntity<>(headersB), Void.class);
        assertEquals(HttpStatus.NOT_FOUND, deleteResponse.getStatusCode());

        ResponseEntity<Business[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headersA), Business[].class);
        assertTrue(java.util.Arrays.stream(listResponse.getBody())
                .anyMatch(b -> b.getId().equals(businessAId)));
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

        WorkPass pass = new WorkPass();
        pass.setEmployeeName("Jane Doe");
        pass.setExpiryDate(LocalDate.of(2026, 11, 1));
        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/work-passes", new HttpEntity<>(pass, headers), WorkPass.class);

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
        Business business = new Business();
        business.setName("");
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(business, headers), String.class);

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
        Business business = new Business();
        business.setName("");
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));

        ResponseEntity<com.chrainx.compliance_tracker.error.ApiError> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(business, headers), com.chrainx.compliance_tracker.error.ApiError.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().error());
        assertTrue(response.getBody().message().contains("name"));
    }

    @Test
    void createBusiness_withNullFinancialYearEnd_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Business business = new Business();
        business.setName("Valid Name");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(business, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateBusiness_withBlankName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        Business updates = new Business();
        updates.setName("");
        updates.setFinancialYearEnd(LocalDate.of(2027, 3, 31));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.PUT, new HttpEntity<>(updates, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
