package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

// Nested under /api/businesses/{businessId} - a work pass never makes sense outside the
// context of the business that employs its holder, so every operation here first has to prove
// the caller owns that business (same findByIdAndOwnerId check BusinessController uses) before
// touching any WorkPass at all.
@RestController
@RequestMapping("/api/businesses/{businessId}/work-passes")
public class WorkPassController {

    private final BusinessRepository businessRepository;
    private final WorkPassRepository workPassRepository;

    @Autowired
    public WorkPassController(BusinessRepository businessRepository, WorkPassRepository workPassRepository) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
    }

    @PostMapping
    public ResponseEntity<?> createWorkPass(@PathVariable Long businessId, @Valid @RequestBody WorkPass workPass,
                                             @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        // Same defensive clearing as issue #66's fix on Business - without this, a client
        // could supply the id of an existing work pass (even one belonging to a different
        // business) and JPA's save() would UPDATE that row instead of inserting a new one.
        workPass.setId(null);
        workPass.setBusiness(business.get());
        return ResponseEntity.ok(workPassRepository.save(workPass));
    }

    @GetMapping
    public ResponseEntity<?> getWorkPasses(@PathVariable Long businessId, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        return ResponseEntity.ok(workPassRepository.findByBusinessId(businessId));
    }

    @DeleteMapping("/{workPassId}")
    public ResponseEntity<?> deleteWorkPass(@PathVariable Long businessId, @PathVariable Long workPassId,
                                             @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Optional<WorkPass> workPass = workPassRepository.findByIdAndBusinessId(workPassId, businessId);
        if (workPass.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Work pass not found."));
        }

        workPassRepository.delete(workPass.get());
        return ResponseEntity.noContent().build();
    }
}
