package com.chrainx.compliance_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void createWorkPass_scopesToOwnedBusiness_andClearsClientSuppliedId() {
        Business business = new Business();
        business.setId(10L);

        WorkPass workPass = new WorkPass();
        workPass.setId(999L); // an attacker-style attempt to target an existing row - see #66
        workPass.setEmployeeName("Jane Doe");
        workPass.setExpiryDate(LocalDate.of(2026, 12, 31));

        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.of(business));
        when(workPassRepository.save(workPass)).thenReturn(workPass);

        ResponseEntity<WorkPass> response = controller.createWorkPass(10L, workPass, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertNull(workPass.getId());
        assertEquals(business, workPass.getBusiness());
    }

    @Test
    void createWorkPass_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<WorkPass> response = controller.createWorkPass(10L, new WorkPass(), currentUser);

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
        when(workPassRepository.findByBusinessId(10L)).thenReturn(List.of(pass));

        ResponseEntity<List<WorkPass>> response = controller.getWorkPasses(10L, currentUser);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void getWorkPasses_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<List<WorkPass>> response = controller.getWorkPasses(10L, currentUser);

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

        ResponseEntity<Void> response = controller.deleteWorkPass(10L, 1L, currentUser);

        assertEquals(204, response.getStatusCode().value());
        verify(workPassRepository).delete(pass);
    }

    @Test
    void deleteWorkPass_returns404_whenBusinessDoesNotBelongToCaller() {
        when(businessRepository.findByIdAndOwnerId(10L, 1L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.deleteWorkPass(10L, 1L, currentUser);

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

        ResponseEntity<Void> response = controller.deleteWorkPass(10L, 1L, currentUser);

        assertEquals(404, response.getStatusCode().value());
        verify(workPassRepository, never()).delete(any());
    }
}
