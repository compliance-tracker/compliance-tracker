package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.AuthResponse;
import com.chrainx.compliance_tracker.auth.AuthRequest;
import com.chrainx.compliance_tracker.auth.EmailVerificationTokenRepository;
import com.chrainx.compliance_tracker.auth.RegistrationResponse;
import com.chrainx.compliance_tracker.auth.UserRepository;
import com.chrainx.compliance_tracker.auth.VerifyEmailRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real, unmocked, full-stack test - same reasoning as WorkPassIntegrationTest (issue #59).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class CustomObligationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private DeadlineSyncService deadlineSyncService;

    @Autowired
    private EmailHasher emailHasher;

    private String registerAndGetToken() {
        String email = "custom-obligation-e2e-" + System.nanoTime() + "@example.com";
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
        BusinessRequest request = new BusinessRequest("Custom Obligation E2E Test Co", LocalDate.of(2026, 12, 31), false, null, null, null);

        return restTemplate.postForEntity("/api/businesses", new HttpEntity<>(request, headers), BusinessResponse.class)
                .getBody().id();
    }

    private List<CustomObligationResponse> listCustomObligations(Long businessId, HttpHeaders headers) {
        ResponseEntity<PageResponse<CustomObligationResponse>> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/custom-obligations", HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<PageResponse<CustomObligationResponse>>() {});
        return response.getBody().content();
    }

    @Test
    void createThenListCustomObligation_forOwnBusiness_succeeds() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        CustomObligationRequest request = new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null);

        ResponseEntity<CustomObligationResponse> createResponse = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations", new HttpEntity<>(request, headers),
                CustomObligationResponse.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        assertEquals("Renew business insurance", createResponse.getBody().name());

        assertTrue(listCustomObligations(businessId, headers).stream()
                .anyMatch(o -> o.name().equals("Renew business insurance")));
    }

    @Test
    void createCustomObligation_forAnotherUsersBusiness_isRejectedWith404() {
        HttpHeaders headersA = authHeaders(registerAndGetToken());
        Long businessAId = createBusiness(headersA);

        HttpHeaders headersB = authHeaders(registerAndGetToken());

        CustomObligationRequest request = new CustomObligationRequest("Should Not Be Created", LocalDate.of(2026, 9, 1), null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessAId + "/custom-obligations", new HttpEntity<>(request, headersB), String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createCustomObligation_withBlankName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        CustomObligationRequest request = new CustomObligationRequest("", LocalDate.of(2026, 9, 1), null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createCustomObligation_withAnExcessivelyLongName_isRejectedWith400() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        CustomObligationRequest request = new CustomObligationRequest("A".repeat(256), LocalDate.of(2026, 9, 1), null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations", new HttpEntity<>(request, headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void updateCustomObligation_changesItsFields() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        Long obligationId = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null), headers),
                CustomObligationResponse.class).getBody().id();

        CustomObligationRequest update = new CustomObligationRequest("Renew business insurance (annual)", LocalDate.of(2026, 10, 1), 12);
        ResponseEntity<CustomObligationResponse> updateResponse = restTemplate.exchange(
                "/api/businesses/" + businessId + "/custom-obligations/" + obligationId, HttpMethod.PUT,
                new HttpEntity<>(update, headers), CustomObligationResponse.class);

        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals("Renew business insurance (annual)", updateResponse.getBody().name());
        assertEquals(LocalDate.of(2026, 10, 1), updateResponse.getBody().dueDate());
        assertEquals(12, updateResponse.getBody().recurrenceMonths());
    }

    @Test
    void deleteCustomObligation_removesIt_soItNoLongerAppearsInList() {
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        Long obligationId = restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("To Be Deleted", LocalDate.of(2026, 9, 1), null), headers),
                CustomObligationResponse.class).getBody().id();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/businesses/" + businessId + "/custom-obligations/" + obligationId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        assertTrue(listCustomObligations(businessId, headers).stream()
                .noneMatch(o -> o.id().equals(obligationId)));
    }

    @Test
    void getDeadlines_includesTheCustomObligationAlongsideTheBuiltInOnes() {
        // The actual point of issue #59: a custom obligation must show up on the same live
        // "deadlines" view as ACRA/GST/work passes, not just exist as its own separate resource.
        // Asserted against the raw JSON, not a deserialized Deadline - Deadline is a plain
        // domain object with no Jackson creator/no-args constructor, since it was only ever
        // meant to be serialized as a response, not deserialized as a request.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null), headers),
                CustomObligationResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"obligationType\":\"CUSTOM\""));
        assertTrue(response.getBody().contains("\"customName\":\"Renew business insurance\""));
        assertTrue(response.getBody().contains("\"dueDate\":\"2026-09-01\""));
        // The built-in ACRA rule must still be there too - a custom obligation is additive, not
        // a replacement for anything RuleEngine already computes.
        assertTrue(response.getBody().contains("\"obligationType\":\"ACRA_ANNUAL_RETURN\""));
    }

    @Test
    void syncDeadlines_persistsACustomObligationsDeadline_dedupedByItsOwnId_notJustDueDate() {
        // Real proof of the dedup fix this issue needed (see DeadlineRecordRepository's own
        // comment): two different custom obligations on the same business, sharing the same due
        // date, must both end up with their own DeadlineRecord - the plain
        // (business, obligationType, dueDate) key alone would have treated the second as a
        // duplicate of the first and silently dropped it.
        HttpHeaders headers = authHeaders(registerAndGetToken());
        Long businessId = createBusiness(headers);

        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null), headers),
                CustomObligationResponse.class);
        restTemplate.postForEntity(
                "/api/businesses/" + businessId + "/custom-obligations",
                new HttpEntity<>(new CustomObligationRequest("Submit annual license renewal", LocalDate.of(2026, 9, 1), null), headers),
                CustomObligationResponse.class);

        deadlineSyncService.syncDeadlines();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/businesses/" + businessId + "/deadlines", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        int customDeadlineCount = countOccurrences(response.getBody(), "\"obligationType\":\"CUSTOM\"");
        assertEquals(2, customDeadlineCount);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
