package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.ObligationType;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Plain unit test - no Spring context/DB involved, so it stays fast. The controller is
// instantiated directly with mocked repositories (Mockito) and a real RuleEngine, since
// RuleEngine is pure logic anyway and cheap to use as-is.
//
// currentUser below stands in for what @AuthenticationPrincipal would inject at runtime -
// since these are plain unit tests calling controller methods directly (no real HTTP request,
// no real JwtAuthenticationFilter involved), we just construct a User and pass it as the
// method argument ourselves.
class BusinessControllerTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final WorkPassRepository workPassRepository = mock(WorkPassRepository.class);
    private final IdempotencyKeyRepository idempotencyKeyRepository = mock(IdempotencyKeyRepository.class);
    private final RuleEngine ruleEngine = new RuleEngine();
    private final BusinessController controller = new BusinessController(
            businessRepository, workPassRepository, idempotencyKeyRepository, ruleEngine);

    private final User currentUser = new User();

    BusinessControllerTest() {
        currentUser.setId(1L);
        currentUser.setEmail("owner@example.com");
    }

    // Controller methods now return ResponseEntity<?> (issue #47 - the success body and the
    // ApiError error body are different types), so tests cast the body to whichever shape a
    // given response actually is.
    @SuppressWarnings("unchecked")
    private List<Deadline> deadlinesBody(ResponseEntity<?> response) {
        return (List<Deadline>) response.getBody();
    }

    private BusinessResponse businessBody(ResponseEntity<?> response) {
        return (BusinessResponse) response.getBody();
    }

    @Test
    void getDeadlines_returnsComputedDeadlines_forExistingBusiness() {
        Business business = new Business();
        business.setId(1L);
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(true);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.findByBusinessId(1L)).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.getDeadlines(1L, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(deadlinesBody(response).stream()
                .anyMatch(d -> d.getObligationType() == ObligationType.ACRA_ANNUAL_RETURN));
    }

    @Test
    void getDeadlines_returns404_whenBusinessNotFound() {
        when(businessRepository.findByIdAndOwnerId(99L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getDeadlines(99L, currentUser);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getDeadlines_returns404_whenBusinessBelongsToAnotherUser() {
        // findByIdAndOwnerId with the *current* user's ID returns empty even though the
        // business exists - because it belongs to someone else. This is the actual ownership
        // enforcement: a business that exists but isn't yours is indistinguishable from one
        // that doesn't exist at all.
        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getDeadlines(1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getDeadlines_includesWorkPassRenewals_whenBusinessHasWorkPasses() {
        Business business = new Business();
        business.setId(1L);
        business.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        business.setGstRegistered(false);

        WorkPass pass = new WorkPass();
        pass.setExpiryDate(LocalDate.of(2026, 11, 1));

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.findByBusinessId(1L)).thenReturn(List.of(pass));

        ResponseEntity<?> response = controller.getDeadlines(1L, currentUser);

        assertTrue(deadlinesBody(response).stream()
                .anyMatch(d -> d.getObligationType() == ObligationType.WORK_PASS_RENEWAL
                        && d.getDueDate().equals(LocalDate.of(2026, 11, 1))));
    }

    // Note on issue #66's IDOR (a client supplying their own "id" so JPA's save() does an
    // UPDATE instead of an INSERT): the tests that used to prove createBusiness/updateBusiness
    // clear a client-supplied id no longer make sense as written, and were removed rather than
    // adapted (issue #46) - BusinessRequest has no id field at all, so there's nothing to
    // "clear" anymore. The IDOR shape is now prevented structurally, by the type system, not by
    // runtime logic a test could still exercise.

    @Test
    void createBusiness_setsOwnerToCurrentUser() {
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, null, null);
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals("Test Co", businessBody(response).name());
    }

    @Test
    void createBusiness_withoutIdempotencyKey_neverTouchesTheIdempotencyRepository() {
        // The header is entirely opt-in (issue #61) - an existing caller that never sends it
        // must see zero behavior change, not just "the same result" but literally no extra
        // queries against a table it doesn't know exists.
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, null, null);
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        controller.createBusiness(request, null, currentUser);

        verifyNoInteractions(idempotencyKeyRepository);
    }

    @Test
    void createBusiness_withNewIdempotencyKey_createsABusiness_andRecordsTheKey() {
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, null, null);
        Business saved = new Business();
        saved.setId(5L);
        saved.setName("Test Co");
        when(idempotencyKeyRepository.findByKeyAndOwnerId("key-123", 1L)).thenReturn(Optional.empty());
        when(businessRepository.save(any())).thenReturn(saved);

        ResponseEntity<?> response = controller.createBusiness(request, "key-123", currentUser);

        assertEquals(5L, businessBody(response).id());
        verify(idempotencyKeyRepository).save(argThat(record ->
                record.getKey().equals("key-123") && record.getOwnerId().equals(1L) && record.getBusinessId().equals(5L)));
    }

    @Test
    void createBusiness_withAlreadyUsedIdempotencyKey_returnsTheOriginalBusiness_withoutCreatingANewOne() {
        // The actual point of the feature: a retried request (same key resent, e.g. after a
        // client-side timeout on a request that had actually already succeeded) must not create
        // a second business.
        Business original = new Business();
        original.setId(5L);
        original.setName("Original Co");
        IdempotencyKey existingKey = new IdempotencyKey();
        existingKey.setBusinessId(5L);
        when(idempotencyKeyRepository.findByKeyAndOwnerId("key-123", 1L)).thenReturn(Optional.of(existingKey));
        when(businessRepository.findById(5L)).thenReturn(Optional.of(original));

        BusinessRequest retriedRequest = new BusinessRequest("Original Co", LocalDate.of(2026, 12, 31), false, null, null);
        ResponseEntity<?> response = controller.createBusiness(retriedRequest, "key-123", currentUser);

        assertEquals(5L, businessBody(response).id());
        verify(businessRepository, never()).save(any());
    }

    // leadTimeDays (issue #53) is optional on the request - these three tests prove
    // createBusiness/updateBusiness handle its absence differently on purpose (see the
    // controller's own comments): create defaults to 14, update preserves whatever the business
    // already had rather than silently resetting it.

    @Test
    void createBusiness_defaultsLeadTimeDaysTo14_whenOmittedFromTheRequest() {
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, null, null);
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals(14, businessBody(response).leadTimeDays());
    }

    @Test
    void createBusiness_usesTheGivenLeadTimeDays_whenPresent() {
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, 30, null);
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals(30, businessBody(response).leadTimeDays());
    }

    @Test
    void updateBusiness_leavesLeadTimeDaysUnchanged_whenOmittedFromTheRequest() {
        Business existing = new Business();
        existing.setId(1L);
        existing.setLeadTimeDays(30);

        BusinessRequest request = new BusinessRequest("New Name", LocalDate.of(2027, 6, 30), true, null, null);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, request, currentUser);

        assertEquals(30, businessBody(response).leadTimeDays());
    }

    @Test
    void updateBusiness_updatesLeadTimeDays_whenPresent() {
        Business existing = new Business();
        existing.setId(1L);
        existing.setLeadTimeDays(14);

        BusinessRequest request = new BusinessRequest("New Name", LocalDate.of(2027, 6, 30), true, 7, null);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, request, currentUser);

        assertEquals(7, businessBody(response).leadTimeDays());
    }

    // incorporationDate (issue #31) - the 18-month first-FYE check only ever runs in
    // createBusiness, per RuleEngine.firstFinancialYearExceedsAcraLimit's own comment on why
    // re-checking it on every update would be wrong.

    @Test
    void createBusiness_withNoIncorporationDate_skipsTheEighteenMonthCheck() {
        BusinessRequest request = new BusinessRequest("Test Co", LocalDate.of(2026, 12, 31), false, null, null);
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void createBusiness_withFirstFyeWithinEighteenMonths_succeeds() {
        BusinessRequest request = new BusinessRequest(
                "Test Co", LocalDate.of(2026, 12, 31), false, null, LocalDate.of(2026, 1, 15));
        when(businessRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(LocalDate.of(2026, 1, 15), businessBody(response).incorporationDate());
    }

    @Test
    void createBusiness_withFirstFyeBeyondEighteenMonths_isRejectedWith400() {
        BusinessRequest request = new BusinessRequest(
                "Test Co", LocalDate.of(2027, 12, 31), false, null, LocalDate.of(2026, 1, 15));

        ResponseEntity<?> response = controller.createBusiness(request, null, currentUser);

        assertEquals(400, response.getStatusCode().value());
        verify(businessRepository, never()).save(any());
    }

    @Test
    void updateBusiness_leavesIncorporationDateUnchanged_whenOmittedFromTheRequest() {
        Business existing = new Business();
        existing.setId(1L);
        existing.setIncorporationDate(LocalDate.of(2020, 1, 1));

        BusinessRequest request = new BusinessRequest("New Name", LocalDate.of(2027, 6, 30), true, null, null);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, request, currentUser);

        assertEquals(LocalDate.of(2020, 1, 1), businessBody(response).incorporationDate());
    }

    @Test
    void updateBusiness_withFinancialYearEndFarPastIncorporation_stillSucceeds() {
        // The behavior the update-time check was deliberately dropped to avoid: a genuinely old
        // business, whose current financialYearEnd is naturally many years past its own
        // incorporationDate, must not get treated as if it just declared an illegal 5-year-long
        // "first" financial year - see RuleEngine.firstFinancialYearExceedsAcraLimit's comment.
        Business existing = new Business();
        existing.setId(1L);
        existing.setIncorporationDate(LocalDate.of(2018, 1, 1));

        BusinessRequest request = new BusinessRequest("Old Co", LocalDate.of(2026, 12, 31), false, null, null);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void getAllBusinesses_returnsOnlyCurrentUsersBusinesses() {
        Business business = new Business();
        business.setId(1L);
        when(businessRepository.findByOwnerId(eq(1L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(business)));

        PageResponse<BusinessResponse> result = controller.getAllBusinesses(currentUser, 0, 20);

        assertEquals(1, result.content().size());
    }

    @Test
    void updateBusiness_appliesChanges_toTheOwnedBusiness() {
        Business existing = new Business();
        existing.setId(1L);
        existing.setName("Old Name");
        existing.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        existing.setGstRegistered(false);

        BusinessRequest request = new BusinessRequest("New Name", LocalDate.of(2027, 6, 30), true, null, null);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("New Name", businessBody(response).name());
        assertEquals(LocalDate.of(2027, 6, 30), businessBody(response).financialYearEnd());
        assertTrue(businessBody(response).gstRegistered());
    }

    @Test
    void updateBusiness_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateBusiness(
                1L, new BusinessRequest("Name", LocalDate.of(2026, 12, 31), false, null, null), currentUser);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteBusiness_deletes_whenOwnedByCaller() {
        Business business = new Business();
        business.setId(1L);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(business));

        ResponseEntity<?> response = controller.deleteBusiness(1L, currentUser);

        assertEquals(204, response.getStatusCode().value());
        verify(businessRepository).delete(business);
    }

    @Test
    void deleteBusiness_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteBusiness(1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(businessRepository, never()).delete(any());
    }
}
