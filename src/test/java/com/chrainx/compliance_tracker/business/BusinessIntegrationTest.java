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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    // Real HTTP, deliberately - proves the Idempotency-Key header (issue #61) actually works
    // end to end against a real Postgres-backed request, not just that the controller method
    // returns the right Java object when called directly.

    @Test
    void createBusiness_withoutIdempotencyKey_createsANewBusinessEveryTime() {
        // The default, unchanged behavior - confirms opting into the feature never accidentally
        // becomes the default for callers who don't send the header at all.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Repeatable Co", LocalDate.of(2026, 12, 31), false);

        Long firstId = restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
        Long secondId = restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();

        assertTrue(!firstId.equals(secondId), "identical requests with no idempotency key must create two separate businesses");
    }

    @Test
    void createBusiness_withTheSameIdempotencyKeyTwice_returnsTheSameBusiness_notADuplicate() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        headers.set("Idempotency-Key", "retry-key-" + System.nanoTime());
        BusinessRequest request = new BusinessRequest("Retried Co", LocalDate.of(2026, 12, 31), false);
        HttpEntity<BusinessRequest> entity = new HttpEntity<>(request, headers);

        Long firstId = restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class).getBody().id();
        Long secondId = restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class).getBody().id();

        assertEquals(firstId, secondId);

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), BusinessResponse[].class);
        long matchingCount = java.util.Arrays.stream(listResponse.getBody())
                .filter(b -> b.name().equals("Retried Co")).count();
        assertEquals(1, matchingCount, "the retry must not have created a second business");
    }

    @Test
    void concurrentCreateRequests_withTheSameIdempotencyKey_resultInExactlyOneBusiness() throws Exception {
        // Regression-style test for the actual race (same shape as issue #42's registration
        // race, see AuthIntegrationTest): two real threads firing the same create request with
        // the same key at genuinely the same instant, against real Postgres. The
        // application-level "does this key already exist" lookup can't prevent both from
        // reaching save() in this window - the DB's unique constraint on
        // (idempotency_key, owner_id) is what actually has to hold, with the loser's business
        // deleted rather than left behind as an orphaned duplicate.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        headers.set("Idempotency-Key", "concurrent-key-" + System.nanoTime());
        BusinessRequest request = new BusinessRequest("Race Co", LocalDate.of(2026, 12, 31), false);
        HttpEntity<BusinessRequest> entity = new HttpEntity<>(request, headers);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);

        List<Future<ResponseEntity<BusinessResponse>>> futures = List.of(
                executor.submit(() -> {
                    startLine.await();
                    return restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class);
                }),
                executor.submit(() -> {
                    startLine.await();
                    return restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class);
                })
        );

        startLine.countDown();
        List<Long> businessIds = futures.stream()
                .map(f -> {
                    try {
                        return f.get(10, TimeUnit.SECONDS).getBody().id();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        executor.shutdown();

        assertEquals(businessIds.get(0), businessIds.get(1),
                "both concurrent requests must resolve to the same business, not two different ones");

        ResponseEntity<BusinessResponse[]> listResponse = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers), BusinessResponse[].class);
        long matchingCount = java.util.Arrays.stream(listResponse.getBody())
                .filter(b -> b.name().equals("Race Co")).count();
        assertEquals(1, matchingCount, "exactly one business must exist, not one per thread");
    }
}
