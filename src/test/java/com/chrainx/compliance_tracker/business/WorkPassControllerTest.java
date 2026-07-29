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

// Same style as BusinessControllerTest - plain unit test, controller instantiated directly
// with mocked repositories, no Spring context/DB involved.
class WorkPassControllerTest {

    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final WorkPassRepository workPassRepository = mock(WorkPassRepository.class);
    private final WorkPassController controller = new WorkPassController(businessRepository, workPassRepository);

    private final User currentUser = new User();

    WorkPassControllerTest() {
        currentUser.setId(1L);
        currentUser.setEmail("owner@example.com");
    }

    // Controller methods now return ResponseEntity<?> (issue #47 - the success body and the
    // ApiError error body are different types), so tests cast the body where they inspect it.
    // getWorkPasses' success body is now a PageResponse (issue #49), not a bare List.
    @SuppressWarnings("unchecked")
    private PageResponse<WorkPassResponse> workPassesBody(ResponseEntity<?> response) {
        return (PageResponse<WorkPassResponse>) response.getBody();
    }

    // Note on issue #66's IDOR: the test that used to prove createWorkPass clears a
    // client-supplied id no longer applies as written (issue #46) - WorkPassRequest has no id
    // field at all, so there's nothing to clear. Prevented structurally now, not by runtime
    // logic a test could still exercise.

    @Test
    void createWorkPass_scopesToOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);

        WorkPassRequest request = new WorkPassRequest("Jane Doe", LocalDate.of(2026, 12, 31));

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createWorkPass(10L, request, currentUser);

        assertEquals(200, response.getStatusCode().value());
        WorkPassResponse body = (WorkPassResponse) response.getBody();
        assertEquals("Jane Doe", body.employeeName());
    }

    @Test
    void createWorkPass_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.createWorkPass(
                10L, new WorkPassRequest("Jane Doe", LocalDate.of(2026, 12, 31)), currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(workPassRepository, never()).save(any());
    }

    @Test
    void getWorkPasses_returnsPassesForOwnedBusiness() {
        Business business = new Business();
        business.setId(10L);
        WorkPass pass = new WorkPass();
        pass.setId(1L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.findByBusinessId(eq(10L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(pass)));

        ResponseEntity<?> response = controller.getWorkPasses(10L, currentUser, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, workPassesBody(response).content().size());
    }

    @Test
    void getWorkPasses_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getWorkPasses(10L, currentUser, 0, 20);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteWorkPass_deletes_whenBusinessAndPassBothBelongToCaller() {
        Business business = new Business();
        business.setId(10L);
        WorkPass pass = new WorkPass();
        pass.setId(1L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.of(pass));

        ResponseEntity<?> response = controller.deleteWorkPass(10L, 1L, currentUser);

        assertEquals(204, response.getStatusCode().value());
        verify(workPassRepository).delete(pass);
    }

    @Test
    void deleteWorkPass_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteWorkPass(10L, 1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(workPassRepository, never()).delete(any());
    }

    @Test
    void deleteWorkPass_returns404_whenPassBelongsToADifferentBusiness() {
        // Business is owned by the caller, but the pass id given belongs to some other
        // business (e.g. one the caller doesn't own) - findByIdAndBusinessId correctly
        // returns empty for that mismatch.
        Business business = new Business();
        business.setId(10L);

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.findByIdAndBusinessId(1L, 10L)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deleteWorkPass(10L, 1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(workPassRepository, never()).delete(any());
    }
}
