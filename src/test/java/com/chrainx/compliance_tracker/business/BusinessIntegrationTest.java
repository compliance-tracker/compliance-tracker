package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.AuthResponse;
import com.chrainx.compliance_tracker.auth.AuthRequest;
import com.chrainx.compliance_tracker.auth.EmailVerificationTokenRepository;
import com.chrainx.compliance_tracker.auth.RegistrationResponse;
import com.chrainx.compliance_tracker.auth.UserRepository;
import com.chrainx.compliance_tracker.auth.VerifyEmailRequest;
import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.security.EmailHasher;

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

    @Autowired
    private DeadlineRecordRepository deadlineRecordRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private EmailHasher emailHasher;

    // Issue #120: register no longer returns usable tokens, and login requires a verified email -
    // same real register/verify/login flow AuthIntegrationTest uses.
    private String registerAndGetToken() {
        String email = "business-e2e-" + System.nanoTime() + "@example.com";
        String password = "a-real-password1";
        restTemplate.postForEntity("/api/auth/register", new AuthRequest(email, password), RegistrationResponse.class);

        Long userId = userRepository.findByEmailHash(emailHasher.hash(email)).orElseThrow().getId();
        String verificationToken = emailVerificationTokenRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getToken();
        restTemplate.postForEntity("/api/auth/verify-email", new VerifyEmailRequest(verificationToken), Void.class);

        return restTemplate.postForEntity("/api/auth/login", new AuthRequest(email, password), AuthResponse.class)
                .getBody().token();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private Long createBusiness(HttpHeaders headers, String name) {
        BusinessRequest request = new BusinessRequest(name, LocalDate.of(2026, 12, 31), false, null, null);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
    }

    // GET /api/businesses returns a PageResponse<BusinessResponse>, not a bare array (issue
    // #49) - a generic type, so ParameterizedTypeReference is needed to deserialize it
    // correctly (plain .class tokens lose generic type info to erasure).
    private List<BusinessResponse> listBusinesses(HttpHeaders headers) {
        ResponseEntity<PageResponse<BusinessResponse>> response = restTemplate.exchange(
                "/api/businesses", HttpMethod.GET, new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<PageResponse<BusinessResponse>>() {});
        return response.getBody().content();
    }

    @Test
    void updateBusiness_appliesChanges_forOwnBusiness() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        BusinessRequest updates = new BusinessRequest("Corrected Name", LocalDate.of(2027, 3, 31), true, null, null);

        ResponseEntity<BusinessResponse> updateResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.PUT, new HttpEntity<>(updates, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals("Corrected Name", updateResponse.getBody().name());
        assertEquals(LocalDate.of(2027, 3, 31), updateResponse.getBody().financialYearEnd());
        assertTrue(updateResponse.getBody().gstRegistered());

        assertTrue(listBusinesses(headers).stream()
                .anyMatch(b -> b.id().equals(businessId) && b.name().equals("Corrected Name")));
    }

    // Real, end-to-end proof of issue #30's actual bug: DeadlineSyncService recomputes deadlines
    // from RuleEngine every sync and inserts any not already persisted, but has no way on its
    // own to remove one that's now WRONG because the FYE it was computed from changed - without
    // updateBusiness's cleanup, the OLD (now-incorrect) unreminded ACRA record would still be
    // sitting there right alongside the newly-synced correct one.
    @Test
    void updateBusiness_changingFinancialYearEnd_removesTheStaleUnremindedAcraRecord_notJustAddsANewOne() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest createRequest = new BusinessRequest(
                "FYE Change Co", LocalDate.of(2026, 12, 31), false, null, null);
        Long businessId = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(createRequest, headers), BusinessResponse.class).getBody().id();

        deadlineSyncService.syncDeadlines();
        LocalDate oldAcraDueDate = LocalDate.of(2027, 7, 31); // 2026-12-31 + 7 months
        assertTrue(deadlineRecordRepository.findAll().stream()
                .anyMatch(r -> r.getBusiness().getId().equals(businessId) && r.getDueDate().equals(oldAcraDueDate)));

        BusinessRequest updateRequest = new BusinessRequest(
                "FYE Change Co", LocalDate.of(2027, 3, 31), false, null, null);
        restTemplate.exchange("/api/businesses/" + businessId, HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), BusinessResponse.class);

        // The stale record must be gone immediately after the update, before the next sync even
        // runs - proving updateBusiness itself cleaned it up, not just that a later sync happened
        // to overwrite it.
        assertTrue(deadlineRecordRepository.findAll().stream()
                .noneMatch(r -> r.getBusiness().getId().equals(businessId) && r.getDueDate().equals(oldAcraDueDate)));

        deadlineSyncService.syncDeadlines();
        LocalDate newAcraDueDate = LocalDate.of(2027, 10, 31); // 2027-03-31 + 7 months
        assertTrue(deadlineRecordRepository.findAll().stream()
                .anyMatch(r -> r.getBusiness().getId().equals(businessId) && r.getDueDate().equals(newAcraDueDate)));
    }

    @Test
    void updateBusiness_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "User A's Business");

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        BusinessRequest updates = new BusinessRequest("Hijacked Name", LocalDate.of(2027, 3, 31), true, null, null);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessAId, HttpMethod.PUT, new HttpEntity<>(updates, headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        assertTrue(listBusinesses(headersA).stream()
                .anyMatch(b -> b.id().equals(businessAId) && b.name().equals("User A's Business")));
    }

    @Test
    void deleteBusiness_removesIt_soItNoLongerAppearsInList() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "To Be Deleted");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        assertTrue(listBusinesses(headers).stream()
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

        assertTrue(listBusinesses(headersA).stream()
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
        BusinessRequest request = new BusinessRequest("", LocalDate.of(2026, 12, 31), false, null, null);

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
        BusinessRequest request = new BusinessRequest("", LocalDate.of(2026, 12, 31), false, null, null);

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), ApiError.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().error());
        assertTrue(response.getBody().message().contains("name"));
    }

    @Test
    void createBusiness_withNullFinancialYearEnd_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Valid Name", null, false, null, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // leadTimeDays (issue #53) - real HTTP, since @Valid only runs during Spring MVC's own
    // request-body binding (same reasoning as the blank-name/null-FYE tests above).

    @Test
    void createBusiness_omittingLeadTimeDays_defaultsItTo14() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Lead Time Default Co", LocalDate.of(2026, 12, 31), false, null, null);

        ResponseEntity<BusinessResponse> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(14, response.getBody().leadTimeDays());
    }

    @Test
    void createBusiness_withAGivenLeadTimeDays_usesIt() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Lead Time Custom Co", LocalDate.of(2026, 12, 31), false, 30, null);

        ResponseEntity<BusinessResponse> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(30, response.getBody().leadTimeDays());
    }

    @Test
    void createBusiness_withLeadTimeDaysBelowOne_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Lead Time Too Low Co", LocalDate.of(2026, 12, 31), false, 0, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createBusiness_withLeadTimeDaysAbove90_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("Lead Time Too High Co", LocalDate.of(2026, 12, 31), false, 91, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateBusiness_omittingLeadTimeDays_leavesItUnchanged() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest createRequest = new BusinessRequest("Lead Time Preserve Co", LocalDate.of(2026, 12, 31), false, 45, null);
        Long businessId = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(createRequest, headers), BusinessResponse.class).getBody().id();

        BusinessRequest updateRequest = new BusinessRequest("Renamed Co", LocalDate.of(2027, 3, 31), true, null, null);
        ResponseEntity<BusinessResponse> updateResponse = restTemplate.exchange(
                "/api/businesses/" + businessId, org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals(45, updateResponse.getBody().leadTimeDays());
    }

    @Test
    void updateBusiness_withLeadTimeDaysBelowOne_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Lead Time Update Reject Co");

        BusinessRequest updateRequest = new BusinessRequest("Lead Time Update Reject Co", LocalDate.of(2026, 12, 31), false, 0, null);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessId, org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // incorporationDate / the Companies Act's 18-month first-FYE cap (issue #31) - real HTTP,
    // since this is a cross-field check inside the controller, not a Bean Validation annotation.

    @Test
    void createBusiness_withNoIncorporationDate_skipsTheEighteenMonthCheck() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest("No Incorporation Date Co", LocalDate.of(2026, 12, 31), false, null, null);

        ResponseEntity<BusinessResponse> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void createBusiness_withFirstFyeWithinEighteenMonths_succeeds() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest(
                "First FYE OK Co", LocalDate.of(2026, 12, 31), false, null, LocalDate.of(2026, 1, 15));

        ResponseEntity<BusinessResponse> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(LocalDate.of(2026, 1, 15), response.getBody().incorporationDate());
    }

    @Test
    void createBusiness_withFirstFyeBeyondEighteenMonths_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest request = new BusinessRequest(
                "First FYE Too Long Co", LocalDate.of(2027, 12, 31), false, null, LocalDate.of(2026, 1, 15));

        ResponseEntity<ApiError> response = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(request, headers), ApiError.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("BAD_REQUEST", response.getBody().error());
    }

    @Test
    void updateBusiness_withFinancialYearEndFarPastIncorporation_stillSucceeds() {
        // The real-world case the update-time check was deliberately dropped to avoid - see
        // RuleEngine.firstFinancialYearExceedsAcraLimit's comment. Simulated here via a real
        // incorporationDate set on create, then a normal update years later.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest createRequest = new BusinessRequest(
                "Long Standing Co", LocalDate.of(2018, 12, 31), false, null, LocalDate.of(2018, 1, 1));
        Long businessId = restTemplate.postForEntity(
                "/api/businesses", new HttpEntity<>(createRequest, headers), BusinessResponse.class).getBody().id();

        BusinessRequest updateRequest = new BusinessRequest(
                "Long Standing Co", LocalDate.of(2026, 12, 31), false, null, null);
        ResponseEntity<BusinessResponse> response = restTemplate.exchange(
                "/api/businesses/" + businessId, org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), BusinessResponse.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(LocalDate.of(2018, 1, 1), response.getBody().incorporationDate());
    }

    @Test
    void updateBusiness_withBlankName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Original Name");

        BusinessRequest updates = new BusinessRequest("", LocalDate.of(2027, 3, 31), false, null, null);

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
        BusinessRequest request = new BusinessRequest("Repeatable Co", LocalDate.of(2026, 12, 31), false, null, null);

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
        BusinessRequest request = new BusinessRequest("Retried Co", LocalDate.of(2026, 12, 31), false, null, null);
        HttpEntity<BusinessRequest> entity = new HttpEntity<>(request, headers);

        Long firstId = restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class).getBody().id();
        Long secondId = restTemplate.postForEntity("/api/businesses", entity, BusinessResponse.class).getBody().id();

        assertEquals(firstId, secondId);

        long matchingCount = listBusinesses(headers).stream()
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
        BusinessRequest request = new BusinessRequest("Race Co", LocalDate.of(2026, 12, 31), false, null, null);
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

        long matchingCount = listBusinesses(headers).stream()
                .filter(b -> b.name().equals("Race Co")).count();
        assertEquals(1, matchingCount, "exactly one business must exist, not one per thread");
    }

    private PageResponse<BusinessResponse> getBusinessesPage(HttpHeaders headers, int page, int size) {
        ResponseEntity<PageResponse<BusinessResponse>> response = restTemplate.exchange(
                "/api/businesses?page=" + page + "&size=" + size, HttpMethod.GET, new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<PageResponse<BusinessResponse>>() {});
        return response.getBody();
    }

    @Test
    void getAllBusinesses_isPaginated() {
        // Real HTTP proof of issue #49: with a small page size, the response is genuinely split
        // across multiple pages with correct metadata and no overlap - not just accepting the
        // query params without doing anything with them.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        for (int i = 0; i < 5; i++) {
            createBusiness(headers, "Paged Co " + i);
        }

        PageResponse<BusinessResponse> firstPage = getBusinessesPage(headers, 0, 2);
        assertEquals(2, firstPage.content().size());
        assertEquals(0, firstPage.page());
        assertEquals(2, firstPage.size());
        assertEquals(5, firstPage.totalElements());
        assertEquals(3, firstPage.totalPages());

        PageResponse<BusinessResponse> secondPage = getBusinessesPage(headers, 1, 2);
        assertEquals(2, secondPage.content().size());
        assertTrue(firstPage.content().stream()
                .noneMatch(b -> secondPage.content().stream().anyMatch(b2 -> b2.id().equals(b.id()))),
                "the two pages must not overlap");
    }

    private PageResponse<DeadlineHistoryEntryResponse> getDeadlineHistory(Long businessId, HttpHeaders headers) {
        ResponseEntity<PageResponse<DeadlineHistoryEntryResponse>> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines/history", HttpMethod.GET, new HttpEntity<>(headers),
                new org.springframework.core.ParameterizedTypeReference<PageResponse<DeadlineHistoryEntryResponse>>() {});
        return response.getBody();
    }

    @Test
    void getDeadlineHistory_showsARealSyncedRecord_withItsReminderSentStatus() {
        // Issue #57: real proof that a real DeadlineRecord (persisted by the real sync job, not
        // hand-constructed) actually shows up in the history endpoint with its real fields.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Deadline History E2E Test Co");

        deadlineSyncService.syncDeadlines();

        PageResponse<DeadlineHistoryEntryResponse> history = getDeadlineHistory(businessId, headers);

        assertTrue(history.content().stream()
                .anyMatch(e -> e.obligationType() == com.chrainx.compliance_tracker.rules.ObligationType.ACRA_ANNUAL_RETURN
                        && !e.reminderSent() && !e.dismissed()));
    }

    @Test
    void getDeadlineHistory_marksARecord_asDismissed_afterARealDismissCall() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers, "Deadline History Dismiss E2E Test Co");
        deadlineSyncService.syncDeadlines();

        String acraDueDate = getDeadlineHistory(businessId, headers).content().stream()
                .filter(e -> e.obligationType() == com.chrainx.compliance_tracker.rules.ObligationType.ACRA_ANNUAL_RETURN)
                .findFirst().orElseThrow().dueDate().toString();

        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/deadlines/dismiss",
                new HttpEntity<>(new DismissDeadlineRequest(
                        com.chrainx.compliance_tracker.rules.ObligationType.ACRA_ANNUAL_RETURN,
                        LocalDate.parse(acraDueDate), null, null), headers),
                DismissedDeadlineResponse.class);

        PageResponse<DeadlineHistoryEntryResponse> history = getDeadlineHistory(businessId, headers);
        assertTrue(history.content().stream()
                .anyMatch(e -> e.obligationType() == com.chrainx.compliance_tracker.rules.ObligationType.ACRA_ANNUAL_RETURN
                        && e.dismissed()));
    }

    @Test
    void getDeadlineHistory_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA, "History Owner A Co");

        HttpHeaders headersB = authHeaders(registerAndGetToken());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessAId + "/deadlines/history", HttpMethod.GET, new HttpEntity<>(headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
