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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private final RuleEngine ruleEngine = new RuleEngine();
    private final BusinessController controller = new BusinessController(businessRepository, workPassRepository, ruleEngine);

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

    private Business businessBody(ResponseEntity<?> response) {
        return (Business) response.getBody();
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

    @Test
    void createBusiness_setsOwnerToCurrentUser() {
        Business business = new Business();
        business.setName("Test Co");

        when(businessRepository.save(business)).thenReturn(business);

        controller.createBusiness(business, currentUser);

        assertEquals(currentUser, business.getOwner());
    }

    @Test
    void createBusiness_clearsClientSuppliedId_toPreventOverwritingAnotherUsersBusiness() {
        // Regression test for the IDOR in issue #66: a request body can carry any "id" the
        // caller wants (e.g. someone else's existing business id). If that id reached save()
        // unchanged, JPA would UPDATE that row instead of inserting a new one - full takeover
        // of another user's business. createBusiness must strip it before saving.
        Business business = new Business();
        business.setId(999L); // pretend this belongs to another user's existing business
        business.setName("Hijacked Co");

        when(businessRepository.save(business)).thenReturn(business);

        controller.createBusiness(business, currentUser);

        assertEquals(null, business.getId());
    }

    @Test
    void getAllBusinesses_returnsOnlyCurrentUsersBusinesses() {
        Business business = new Business();
        business.setId(1L);
        when(businessRepository.findByOwnerId(1L)).thenReturn(List.of(business));

        List<Business> result = controller.getAllBusinesses(currentUser);

        assertEquals(1, result.size());
    }

    @Test
    void updateBusiness_appliesChanges_toTheOwnedBusiness() {
        Business existing = new Business();
        existing.setId(1L);
        existing.setName("Old Name");
        existing.setFinancialYearEnd(LocalDate.of(2026, 12, 31));
        existing.setGstRegistered(false);

        Business updates = new Business();
        updates.setName("New Name");
        updates.setFinancialYearEnd(LocalDate.of(2027, 6, 30));
        updates.setGstRegistered(true);

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        ResponseEntity<?> response = controller.updateBusiness(1L, updates, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("New Name", businessBody(response).getName());
        assertEquals(LocalDate.of(2027, 6, 30), businessBody(response).getFinancialYearEnd());
        assertTrue(businessBody(response).isGstRegistered());
    }

    @Test
    void updateBusiness_ignoresClientSuppliedIdAndOwner() {
        // Same IDOR-avoidance shape as issue #66: updateBusiness never saves the client-supplied
        // `updates` object directly, only copies its mutable fields onto the already-fetched,
        // already-owned entity - so a client-supplied id/owner on the request body can't do
        // anything at all, there's no code path that would ever read them.
        Business existing = new Business();
        existing.setId(1L);
        existing.setName("Real Business");

        Business updates = new Business();
        updates.setId(999L); // attacker-style attempt, should simply be ignored
        updates.setName("Updated Name");

        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.of(existing));
        when(businessRepository.save(existing)).thenReturn(existing);

        controller.updateBusiness(1L, updates, currentUser);

        assertEquals(1L, existing.getId());
    }

    @Test
    void updateBusiness_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(1L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateBusiness(1L, new Business(), currentUser);

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
