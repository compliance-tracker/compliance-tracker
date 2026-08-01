package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.rules.ObligationType;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Same style as CustomObligationControllerTest/WorkPassControllerTest - plain unit test,
// controller instantiated directly with mocked repositories, no Spring context/DB involved.
class DeadlineDismissalControllerTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final CustomObligationRepository customObligationRepository = mock(CustomObligationRepository.class);
    private final DismissedDeadlineRepository dismissedDeadlineRepository = mock(DismissedDeadlineRepository.class);
    private final DeadlineDismissalController controller =
            new DeadlineDismissalController(businessRepository, customObligationRepository, dismissedDeadlineRepository);

    private final User currentUser = new User();

    DeadlineDismissalControllerTest() {
        currentUser.setId(1L);
        currentUser.setEmail("owner@example.com");
    }

    private DismissedDeadlineResponse body(ResponseEntity<?> response) {
        return (DismissedDeadlineResponse) response.getBody();
    }

    @Test
    void dismiss_createsARowForABuiltInObligationType() {
        Business business = new Business();
        business.setId(10L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(dismissedDeadlineRepository.findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationIdIsNull(
                10L, ObligationType.ACRA_ANNUAL_RETURN, LocalDate.of(2027, 7, 31))).thenReturn(Optional.empty());
        when(dismissedDeadlineRepository.save(any())).thenAnswer(invocation -> {
            DismissedDeadline saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        DismissDeadlineRequest request = new DismissDeadlineRequest(
                ObligationType.ACRA_ANNUAL_RETURN, LocalDate.of(2027, 7, 31), null, null);
        ResponseEntity<?> response = controller.dismiss(10L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(ObligationType.ACRA_ANNUAL_RETURN, body(response).obligationType());
        assertEquals(LocalDate.of(2027, 7, 31), body(response).dueDate());
        assertNull(body(response).customObligationId());
    }

    @Test
    void dismiss_isIdempotent_returnsTheExistingRowInsteadOfADuplicate() {
        Business business = new Business();
        business.setId(10L);
        DismissedDeadline existing = new DismissedDeadline();
        existing.setId(5L);
        existing.setBusiness(business);
        existing.setObligationType(ObligationType.GST_F5);
        existing.setDueDate(LocalDate.of(2026, 10, 30));

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(dismissedDeadlineRepository.findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationIdIsNull(
                10L, ObligationType.GST_F5, LocalDate.of(2026, 10, 30))).thenReturn(Optional.of(existing));

        DismissDeadlineRequest request = new DismissDeadlineRequest(
                ObligationType.GST_F5, LocalDate.of(2026, 10, 30), null, null);
        ResponseEntity<?> response = controller.dismiss(10L, request, currentUser);

        assertEquals(5L, body(response).id());
        verify(dismissedDeadlineRepository, never()).save(any());
    }

    @Test
    void dismiss_returns404_whenCustomObligationIdDoesNotBelongToTheBusiness() {
        Business business = new Business();
        business.setId(10L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.findByIdAndBusinessId(99L, 10L)).thenReturn(Optional.empty());

        DismissDeadlineRequest request = new DismissDeadlineRequest(
                ObligationType.CUSTOM, LocalDate.of(2026, 9, 1), 99L, "Renew insurance");
        ResponseEntity<?> response = controller.dismiss(10L, request, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(dismissedDeadlineRepository, never()).save(any());
    }

    @Test
    void dismiss_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.dismiss(
                10L, new DismissDeadlineRequest(ObligationType.GST_F5, LocalDate.of(2026, 10, 30), null, null), currentUser);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getDismissed_returnsEveryDismissedDeadlineForOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);
        DismissedDeadline dismissed = new DismissedDeadline();
        dismissed.setId(1L);
        dismissed.setBusiness(business);
        dismissed.setObligationType(ObligationType.WORK_PASS_RENEWAL);
        dismissed.setDueDate(LocalDate.of(2026, 11, 1));

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(dismissedDeadlineRepository.findByBusinessId(10L)).thenReturn(List.of(dismissed));

        ResponseEntity<?> response = controller.getDismissed(10L, currentUser);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<DismissedDeadlineResponse> results = (List<DismissedDeadlineResponse>) response.getBody();
        assertEquals(1, results.size());
        assertEquals(ObligationType.WORK_PASS_RENEWAL, results.get(0).obligationType());
    }

    @Test
    void undismiss_deletesTheRow_whenItBelongsToTheOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);
        DismissedDeadline dismissed = new DismissedDeadline();
        dismissed.setId(1L);
        dismissed.setBusiness(business);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(dismissedDeadlineRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.of(dismissed));

        ResponseEntity<?> response = controller.undismiss(10L, 1L, currentUser);

        assertEquals(204, response.getStatusCode().value());
        verify(dismissedDeadlineRepository).delete(dismissed);
    }

    @Test
    void undismiss_returns404_whenTheDismissedRowDoesNotBelongToThisBusiness() {
        Business business = new Business();
        business.setId(10L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(dismissedDeadlineRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.undismiss(10L, 1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(dismissedDeadlineRepository, never()).delete(any());
    }
}
