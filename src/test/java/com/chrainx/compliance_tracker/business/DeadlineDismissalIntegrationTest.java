package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.AuthResponse;
import com.chrainx.compliance_tracker.auth.AuthRequest;
import com.chrainx.compliance_tracker.auth.EmailVerificationTokenRepository;
import com.chrainx.compliance_tracker.auth.RegistrationResponse;
import com.chrainx.compliance_tracker.auth.UserRepository;
import com.chrainx.compliance_tracker.auth.VerifyEmailRequest;
import com.chrainx.compliance_tracker.rules.ObligationType;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import com.chrainx.compliance_tracker.security.EmailHasher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as WorkPassIntegrationTest/
// CustomObligationIntegrationTest (issue #34).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class DeadlineDismissalIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private EmailHasher emailHasher;

    @Autowired
    private DeadlineSyncService deadlineSyncService;

    private String registerAndGetToken() {
        String email = "deadline-dismissal-e2e-" + System.nanoTime() + "@example.com";
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

    private Long createBusiness(HttpHeaders headers) {
        BusinessRequest request = new BusinessRequest("Deadline Dismissal E2E Test Co", LocalDate.of(2026, 12, 31), true, null, null, null);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
    }

    // Deadline has no Jackson creator (it's only ever meant to be a response, same reasoning
    // CustomObligationIntegrationTest already documents) - deserialize into plain Maps instead.
    private List<Map<String, Object>> getDeadlines(Long businessId, HttpHeaders headers) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return response.getBody();
    }

    private String dueDateOf(List<Map<String, Object>> deadlines, String obligationType) {
        return deadlines.stream()
                .filter(d -> obligationType.equals(d.get("obligationType")))
                .findFirst().orElseThrow()
                .get("dueDate").toString();
    }

    @Test
    void dismiss_removesTheDeadlineFromTheLiveView_withoutAffectingOtherObligationTypes() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        String acraDueDate = dueDateOf(getDeadlines(businessId, headers), "ACRA_ANNUAL_RETURN");

        ResponseEntity<DismissedDeadlineResponse> dismissResponse = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/deadlines/dismiss",
                new HttpEntity<>(new DismissDeadlineRequest(ObligationType.ACRA_ANNUAL_RETURN, LocalDate.parse(acraDueDate), null, null), headers),
                DismissedDeadlineResponse.class);
        assertEquals(HttpStatus.OK, dismissResponse.getStatusCode());

        List<Map<String, Object>> afterDismissal = getDeadlines(businessId, headers);
        assertFalse(afterDismissal.stream().anyMatch(d -> "ACRA_ANNUAL_RETURN".equals(d.get("obligationType"))));
        // GST F5 is untouched - dismissing one obligation type doesn't hide everything.
        assertTrue(afterDismissal.stream().anyMatch(d -> "GST_F5".equals(d.get("obligationType"))));
    }

    @Test
    void dismiss_isIdempotent_repeatingItDoesNotCreateDuplicateDismissedRows() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);
        String acraDueDate = dueDateOf(getDeadlines(businessId, headers), "ACRA_ANNUAL_RETURN");
        HttpEntity<DismissDeadlineRequest> request = new HttpEntity<>(
                new DismissDeadlineRequest(ObligationType.ACRA_ANNUAL_RETURN, LocalDate.parse(acraDueDate), null, null), headers);

        restTemplate.postForEntity("/api/businesses/" + businessId + "/deadlines/dismiss", request, DismissedDeadlineResponse.class);
        restTemplate.postForEntity("/api/businesses/" + businessId + "/deadlines/dismiss", request, DismissedDeadlineResponse.class);

        List<DismissedDeadlineResponse> dismissed = listDismissed(businessId, headers);
        assertEquals(1, dismissed.size());
    }

    @Test
    void undismiss_bringsTheDeadlineBackIntoTheLiveView() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);
        String acraDueDate = dueDateOf(getDeadlines(businessId, headers), "ACRA_ANNUAL_RETURN");

        Long dismissedId = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/deadlines/dismiss",
                new HttpEntity<>(new DismissDeadlineRequest(ObligationType.ACRA_ANNUAL_RETURN, LocalDate.parse(acraDueDate), null, null), headers),
                DismissedDeadlineResponse.class).getBody().id();
        assertFalse(getDeadlines(businessId, headers).stream().anyMatch(d -> "ACRA_ANNUAL_RETURN".equals(d.get("obligationType"))));

        ResponseEntity<Void> undismissResponse = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines/dismiss/" + dismissedId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, undismissResponse.getStatusCode());

        assertTrue(getDeadlines(businessId, headers).stream().anyMatch(d -> "ACRA_ANNUAL_RETURN".equals(d.get("obligationType"))));
        assertTrue(listDismissed(businessId, headers).isEmpty());
    }

    @Test
    void dismiss_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA);
        String acraDueDate = dueDateOf(getDeadlines(businessAId, headersA), "ACRA_ANNUAL_RETURN");

        HttpHeaders headersB = authHeaders(registerAndGetToken());
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessAId + "/deadlines/dismiss",
                new HttpEntity<>(new DismissDeadlineRequest(ObligationType.ACRA_ANNUAL_RETURN, LocalDate.parse(acraDueDate), null, null), headersB),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void dismissedDeadline_isExcludedFromTheAutomatedReminderDispatch() {
        // The actual point of issue #34: dismissing a deadline must stop it from triggering an
        // automated reminder too, not just hide it from the UI. A short lead time so today
        // (whatever "today" is when this test runs) always falls inside the due-soon window,
        // regardless of the real computed GST/custom-obligation dates. A custom obligation due
        // tomorrow (not ACRA - its real due date, FYE + 7 months, can land far outside any
        // reasonable lead time window depending on the current date, making it an unreliable
        // "still there" signal here) proves dismissing GST doesn't silently suppress the whole
        // business's queue.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        BusinessRequest businessRequest = new BusinessRequest("Dismiss Dispatch E2E Test Co", LocalDate.of(2026, 12, 31), true, 90, null, null);
        Long businessId = restTemplate.postForEntity("/api/businesses", new HttpEntity<>(businessRequest, headers), BusinessResponse.class)
                .getBody().id();
        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("Renew business insurance", LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE).plusDays(1), null), headers),
                CustomObligationResponse.class);

        String gstDueDate = dueDateOf(getDeadlines(businessId, headers), "GST_F5");
        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/deadlines/dismiss",
                new HttpEntity<>(new DismissDeadlineRequest(ObligationType.GST_F5, LocalDate.parse(gstDueDate), null, null), headers),
                DismissedDeadlineResponse.class);

        deadlineSyncService.syncDeadlines();
        List<DeadlineRecord> dueSoon = deadlineSyncService.findDueSoonAndUnreminded(LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE));

        assertFalse(dueSoon.stream().anyMatch(record ->
                record.getBusiness().getId().equals(businessId) && record.getObligationType() == ObligationType.GST_F5));
        // The still-not-dismissed custom obligation for this same business must still be there -
        // dismissing one obligation type doesn't silently suppress the whole business's queue.
        assertTrue(dueSoon.stream().anyMatch(record ->
                record.getBusiness().getId().equals(businessId) && record.getObligationType() == ObligationType.CUSTOM));
    }

    private List<DismissedDeadlineResponse> listDismissed(Long businessId, HttpHeaders headers) {
        ResponseEntity<List<DismissedDeadlineResponse>> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines/dismissed", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<DismissedDeadlineResponse>>() {});
        return response.getBody();
    }
}
