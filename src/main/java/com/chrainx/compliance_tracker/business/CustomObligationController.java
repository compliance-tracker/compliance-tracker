package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

// Nested under /api/businesses/{businessId} - same ownership-scoping shape as
// WorkPassController: every operation first proves the caller owns the business before
// touching any CustomObligation at all (issue #59).
@RestController
@RequestMapping("/api/businesses/{businessId}/custom-obligations")
public class CustomObligationController {

    private final BusinessRepository businessRepository;
    private final CustomObligationRepository customObligationRepository;
    private final DeadlineRecordRepository deadlineRecordRepository;

    @Autowired
    public CustomObligationController(BusinessRepository businessRepository,
                                       CustomObligationRepository customObligationRepository,
                                       DeadlineRecordRepository deadlineRecordRepository) {
        this.businessRepository = businessRepository;
        this.customObligationRepository = customObligationRepository;
        this.deadlineRecordRepository = deadlineRecordRepository;
    }

    // Binds CustomObligationRequest, not the entity (same #66/#46 IDOR-avoidance reasoning as
    // every other create endpoint in this app).
    @PostMapping
    public ResponseEntity<?> createCustomObligation(@PathVariable Long businessId,
                                                     @Valid @RequestBody CustomObligationRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        CustomObligation obligation = new CustomObligation();
        obligation.setName(request.name());
        obligation.setDueDate(request.dueDate());
        obligation.setRecurrenceMonths(request.recurrenceMonths());
        obligation.setBusiness(business.get());
        return ResponseEntity.ok(CustomObligationResponse.from(customObligationRepository.save(obligation)));
    }

    // Paginated (same reasoning as every other list endpoint in this app, issue #49).
    @GetMapping
    public ResponseEntity<?> getCustomObligations(@PathVariable Long businessId, @AuthenticationPrincipal User currentUser,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Page<CustomObligation> obligations = customObligationRepository.findByBusinessId(businessId, PageResponse.pageable(page, size));
        return ResponseEntity.ok(PageResponse.from(obligations.map(CustomObligationResponse::from)));
    }

    // @Transactional: deleteByCustomObligationIdAndReminderSentFalse below is a derived delete
    // query, same InvalidDataAccessApiUsageException-if-missing reasoning as every other derived
    // delete elsewhere in this app (e.g. AuthController.forgotPassword) - needs an actual
    // transaction already open on the calling thread.
    @Transactional
    @PutMapping("/{customObligationId}")
    public ResponseEntity<?> updateCustomObligation(@PathVariable Long businessId, @PathVariable Long customObligationId,
                                                     @Valid @RequestBody CustomObligationRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Optional<CustomObligation> obligation = customObligationRepository.findByIdAndBusinessId(customObligationId, businessId);
        if (obligation.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Custom obligation not found."));
        }

        // Same #30 lesson as FYE changes: a changed due date/recurrence leaves the old,
        // not-yet-reminded DeadlineRecord stale (computed from the pre-edit values) - without
        // clearing it, the next sync's dedupe check would insert the newly-correct one
        // alongside the stale one instead of replacing it, and a reminder could still fire off
        // the wrong date.
        deadlineRecordRepository.deleteByCustomObligationIdAndReminderSentFalse(customObligationId);

        CustomObligation existing = obligation.get();
        existing.setName(request.name());
        existing.setDueDate(request.dueDate());
        existing.setRecurrenceMonths(request.recurrenceMonths());
        return ResponseEntity.ok(CustomObligationResponse.from(customObligationRepository.save(existing)));
    }

    @DeleteMapping("/{customObligationId}")
    public ResponseEntity<?> deleteCustomObligation(@PathVariable Long businessId, @PathVariable Long customObligationId,
                                                     @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Optional<CustomObligation> obligation = customObligationRepository.findByIdAndBusinessId(customObligationId, businessId);
        if (obligation.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Custom obligation not found."));
        }

        // Its DeadlineRecords (both reminded and unreminded) are removed by the DB's own
        // ON DELETE CASCADE on custom_obligation_id (V11 migration) - no explicit cleanup here,
        // same pattern as deleting a business cascading onto its work passes/deadlines (V3).
        customObligationRepository.delete(obligation.get());
        return ResponseEntity.noContent().build();
    }
}
