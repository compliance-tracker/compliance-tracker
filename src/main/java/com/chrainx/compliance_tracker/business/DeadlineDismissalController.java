package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Issue #34: manually mark a live-computed deadline as handled, independent of
// DeadlineRecord.reminderSent (see DismissedDeadline's own comment for why this is a separate
// table matched by natural key, not a flag on DeadlineRecord). Same ownership-scoping shape as
// WorkPassController/CustomObligationController - every operation proves the caller owns the
// business before touching anything.
@RestController
@RequestMapping("/api/businesses/{businessId}/deadlines")
public class DeadlineDismissalController {

    private final BusinessRepository businessRepository;
    private final CustomObligationRepository customObligationRepository;
    private final DismissedDeadlineRepository dismissedDeadlineRepository;

    @Autowired
    public DeadlineDismissalController(BusinessRepository businessRepository,
                                        CustomObligationRepository customObligationRepository,
                                        DismissedDeadlineRepository dismissedDeadlineRepository) {
        this.businessRepository = businessRepository;
        this.customObligationRepository = customObligationRepository;
        this.dismissedDeadlineRepository = dismissedDeadlineRepository;
    }

    // Idempotent - dismissing an already-dismissed deadline just returns the existing row rather
    // than creating a duplicate or erroring, since the caller's actual intent ("I don't want to
    // see this one anymore") is already satisfied either way.
    @PostMapping("/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable Long businessId, @Valid @RequestBody DismissDeadlineRequest request,
                                      @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        // A supplied customObligationId must actually belong to this business too - otherwise a
        // caller could store a dismissal row referencing another business's custom obligation.
        if (request.customObligationId() != null
                && customObligationRepository.findByIdAndBusinessId(request.customObligationId(), businessId).isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Custom obligation not found."));
        }

        Optional<DismissedDeadline> existing = findExisting(businessId, request);
        if (existing.isPresent()) {
            return ResponseEntity.ok(DismissedDeadlineResponse.from(existing.get()));
        }

        DismissedDeadline dismissed = new DismissedDeadline();
        dismissed.setBusiness(business.get());
        dismissed.setObligationType(request.obligationType());
        dismissed.setDueDate(request.dueDate());
        if (request.customObligationId() != null) {
            dismissed.setCustomObligation(customObligationRepository.getReferenceById(request.customObligationId()));
            dismissed.setCustomName(request.customName());
        }
        dismissed.setDismissedAt(Instant.now());
        return ResponseEntity.ok(DismissedDeadlineResponse.from(dismissedDeadlineRepository.save(dismissed)));
    }

    // Lets the frontend show what's been dismissed (and offer an undo) - without this, a
    // mistaken dismissal would have no way back short of a direct DB edit.
    @GetMapping("/dismissed")
    public ResponseEntity<?> getDismissed(@PathVariable Long businessId, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        List<DismissedDeadlineResponse> dismissed = dismissedDeadlineRepository.findByBusinessId(businessId).stream()
                .map(DismissedDeadlineResponse::from)
                .toList();
        return ResponseEntity.ok(dismissed);
    }

    @DeleteMapping("/dismiss/{dismissedDeadlineId}")
    public ResponseEntity<?> undismiss(@PathVariable Long businessId, @PathVariable Long dismissedDeadlineId,
                                        @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(businessId, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Optional<DismissedDeadline> dismissed = dismissedDeadlineRepository.findByIdAndBusinessId(dismissedDeadlineId, businessId);
        if (dismissed.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Dismissed deadline not found."));
        }

        dismissedDeadlineRepository.delete(dismissed.get());
        return ResponseEntity.noContent().build();
    }

    private Optional<DismissedDeadline> findExisting(Long businessId, DismissDeadlineRequest request) {
        return request.customObligationId() != null
                ? dismissedDeadlineRepository.findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationId(
                        businessId, request.obligationType(), request.dueDate(), request.customObligationId())
                : dismissedDeadlineRepository.findByBusinessIdAndObligationTypeAndDueDateAndCustomObligationIdIsNull(
                        businessId, request.obligationType(), request.dueDate());
    }
}
