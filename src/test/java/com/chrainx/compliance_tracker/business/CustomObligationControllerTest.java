package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// Same style as WorkPassControllerTest - plain unit test, controller instantiated directly
// with mocked repositories, no Spring context/DB involved.
class CustomObligationControllerTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final CustomObligationRepository customObligationRepository = mock(CustomObligationRepository.class);
    private final DeadlineRecordRepository deadlineRecordRepository = mock(DeadlineRecordRepository.class);
    private final CustomObligationController controller =
            new CustomObligationController(businessRepository, customObligationRepository, deadlineRecordRepository);

    private final User currentUser = new User();

    CustomObligationControllerTest() {
        currentUser.setId(1L);
        currentUser.setEmail("owner@example.com");
    }

    @SuppressWarnings("unchecked")
    private PageResponse<CustomObligationResponse> obligationsBody(ResponseEntity<?> response) {
        return (PageResponse<CustomObligationResponse>) response.getBody();
    }

    @Test
    void createCustomObligation_scopesToOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);

        CustomObligationRequest request = new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createCustomObligation(10L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
        CustomObligationResponse body = (CustomObligationResponse) response.getBody();
        assertEquals("Renew business insurance", body.name());
        assertEquals(LocalDate.of(2026, 9, 1), body.dueDate());
    }

    @Test
    void createCustomObligation_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.createCustomObligation(
                10L, new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 9, 1), null), currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(customObligationRepository, never()).save(any());
    }

    @Test
    void getCustomObligations_returnsObligationsForOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);
        CustomObligation obligation = new CustomObligation();
        obligation.setId(1L);
        obligation.setName("Renew business insurance");

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.findByBusinessId(eq(10L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(obligation)));

        ResponseEntity<?> response = controller.getCustomObligations(10L, currentUser, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, obligationsBody(response).content().size());
    }

    @Test
    void getCustomObligations_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getCustomObligations(10L, currentUser, 0, 20);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void updateCustomObligation_updatesFields_andClearsAnyStaleUnremindedDeadline() {
        // Same #30 lesson as a business's FYE changing - a changed due date/recurrence must
        // clear out the old, now-stale unreminded DeadlineRecord, or the next sync would insert
        // the newly-correct one alongside it instead of replacing it.
        Business business = new Business();
        business.setId(10L);
        CustomObligation existing = new CustomObligation();
        existing.setId(1L);
        existing.setName("Renew business insurance");
        existing.setDueDate(LocalDate.of(2026, 9, 1));

        CustomObligationRequest request = new CustomObligationRequest("Renew business insurance", LocalDate.of(2026, 10, 1), 12);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.of(existing));
        when(customObligationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.updateCustomObligation(10L, 1L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
        CustomObligationResponse body = (CustomObligationResponse) response.getBody();
        assertEquals(LocalDate.of(2026, 10, 1), body.dueDate());
        assertEquals(12, body.recurrenceMonths());
        verify(deadlineRecordRepository).deleteByCustomObligationIdAndReminderSentFalse(1L);
    }

    @Test
    void updateCustomObligation_returns404_whenObligationBelongsToADifferentBusiness() {
        Business business = new Business();
        business.setId(10L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.updateCustomObligation(
                10L, 1L, new CustomObligationRequest("Renamed", LocalDate.of(2026, 9, 1), null), currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(customObligationRepository, never()).save(any());
    }

    @Test
    void deleteCustomObligation_deletes_whenBusinessAndObligationBothBelongToCaller() {
        Business business = new Business();
        business.setId(10L);
        CustomObligation obligation = new CustomObligation();
        obligation.setId(1L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(customObligationRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.of(obligation));

        ResponseEntity<?> response = controller.deleteCustomObligation(10L, 1L, currentUser);

        assertEquals(204, response.getStatusCode().value());
        verify(customObligationRepository).delete(obligation);
    }

    @Test
    void deleteCustomObligation_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteCustomObligation(10L, 1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(customObligationRepository, never()).delete(any());
    }
}
