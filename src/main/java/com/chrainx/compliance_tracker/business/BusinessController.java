package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.GstFilingFrequency;
import com.chrainx.compliance_tracker.rules.ObligationType;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// @RestController: marks this class as an HTTP handler whose return values get serialized
// straight to JSON, instead of being treated as a view template name.
// @RequestMapping: base path prefix shared by every method below.
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessRepository businessRepository;
    private final WorkPassRepository workPassRepository;
    private final CustomObligationRepository customObligationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final DeadlineRecordRepository deadlineRecordRepository;
    private final DismissedDeadlineRepository dismissedDeadlineRepository;
    private final RuleEngine ruleEngine;

    // @Autowired: Spring sees this constructor needs a BusinessRepository, WorkPassRepository,
    // CustomObligationRepository, IdempotencyKeyRepository, DeadlineRecordRepository,
    // DismissedDeadlineRepository, and RuleEngine, and since it already knows how to create all
    // seven (repositories are auto-implemented interfaces, RuleEngine is @Component), it builds
    // them and passes them in automatically - we never call `new BusinessController(...)` ourselves.
    @Autowired
    public BusinessController(BusinessRepository businessRepository, WorkPassRepository workPassRepository,
                               CustomObligationRepository customObligationRepository,
                               IdempotencyKeyRepository idempotencyKeyRepository,
                               DeadlineRecordRepository deadlineRecordRepository,
                               DismissedDeadlineRepository dismissedDeadlineRepository, RuleEngine ruleEngine) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
        this.customObligationRepository = customObligationRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.deadlineRecordRepository = deadlineRecordRepository;
        this.dismissedDeadlineRepository = dismissedDeadlineRepository;
        this.ruleEngine = ruleEngine;
    }

    // @AuthenticationPrincipal: injects whatever JwtAuthenticationFilter put into the
    // SecurityContext as the authenticated principal - which we set to the actual User entity,
    // so this parameter just is the logged-in user, no extra lookup needed here.
    //
    // Binds a BusinessRequest, not the Business entity itself (issue #46) - there's no id/owner
    // field on the request DTO at all, so the #66 IDOR shape (a client supplying their own id,
    // JPA's save() silently doing an UPDATE instead of an INSERT) is structurally impossible
    // here, not just defended against by remembering to clear a field.
    //
    // Idempotency-Key header (issue #61, optional - entirely opt-in, existing callers that never
    // send it see no behavior change at all): a network retry after a timeout (the first request
    // actually succeeded server-side, the client just never saw the response) would otherwise
    // create a duplicate business. A client that generates one key per logical "create this
    // business" attempt and resends the same key on retry gets the original business back
    // instead of a second one.
    @PostMapping
    public ResponseEntity<?> createBusiness(@Valid @RequestBody BusinessRequest request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @AuthenticationPrincipal User currentUser) {
        if (exceedsFirstYearAcraLimit(request)) {
            return ResponseEntity.status(400).body(new ApiError("BAD_REQUEST",
                    "For a first financial year, financialYearEnd cannot be more than 18 months after incorporationDate."));
        }

        if (idempotencyKey != null) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKeyAndOwnerId(idempotencyKey, currentUser.getId());
            if (existing.isPresent()) {
                return ResponseEntity.ok(
                        BusinessResponse.from(businessRepository.findById(existing.get().getBusinessId()).orElseThrow()));
            }
        }

        Business business = new Business();
        business.setName(request.name());
        business.setFinancialYearEnd(request.financialYearEnd());
        business.setGstRegistered(request.gstRegistered());
        // leadTimeDays (issue #53) is optional on the request - default to 14 (the old hardcoded
        // behavior) when the client doesn't send one, same idiom as Business.leadTimeDays' own
        // Java-side default.
        business.setLeadTimeDays(request.leadTimeDays() != null ? request.leadTimeDays() : 14);
        business.setIncorporationDate(request.incorporationDate());
        // Same optional-with-a-default idiom as leadTimeDays above (issue #45) - QUARTERLY is
        // the pre-existing behavior every business had before this field existed.
        business.setGstFilingFrequency(
                request.gstFilingFrequency() != null ? request.gstFilingFrequency() : GstFilingFrequency.QUARTERLY);
        business.setOwner(currentUser);
        business = businessRepository.save(business);

        if (idempotencyKey != null) {
            business = claimIdempotencyKeyOrReturnTheWinners(idempotencyKey, currentUser.getId(), business);
        }

        return ResponseEntity.ok(BusinessResponse.from(business));
    }

    // Cross-field, so it can't be a Bean Validation annotation on BusinessRequest itself (same
    // reasoning as AuthController.isTooWeak) - only meaningful when incorporationDate is
    // actually present, since a business that's never set one has nothing to check against.
    private boolean exceedsFirstYearAcraLimit(BusinessRequest request) {
        return request.incorporationDate() != null
                && ruleEngine.firstFinancialYearExceedsAcraLimit(request.incorporationDate(), request.financialYearEnd());
    }

    // The lookup above isn't atomic with this insert - two concurrent requests carrying the same
    // key can both pass that check before either commits (same race shape as issue #42's
    // registration race), each creating its own real Business row before either records the key.
    // The unique constraint on (idempotency_key, owner_id) is the actual enforcement point: the
    // loser's insert fails here, and rather than leaving its already-created Business as an
    // orphaned duplicate, it's deleted and the winner's business is returned instead - the whole
    // point of idempotency is that a retry never results in two businesses existing.
    private Business claimIdempotencyKeyOrReturnTheWinners(String idempotencyKey, Long ownerId, Business justCreated) {
        IdempotencyKey record = new IdempotencyKey();
        record.setKey(idempotencyKey);
        record.setOwnerId(ownerId);
        record.setBusinessId(justCreated.getId());

        try {
            idempotencyKeyRepository.save(record);
            return justCreated;
        } catch (DataIntegrityViolationException e) {
            businessRepository.delete(justCreated);
            IdempotencyKey winner = idempotencyKeyRepository.findByKeyAndOwnerId(idempotencyKey, ownerId).orElseThrow();
            return businessRepository.findById(winner.getBusinessId()).orElseThrow();
        }
    }

    // Paginated (issue #49) - page is 0-indexed, size defaults to 20 and is capped at 100
    // (PageResponse.pageable), so a caller with hundreds of businesses (e.g. an accounting firm
    // managing many clients) doesn't get every row back in one unbounded response.
    @GetMapping
    public PageResponse<BusinessResponse> getAllBusinesses(@AuthenticationPrincipal User currentUser,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        Page<Business> businesses = businessRepository.findByOwnerId(currentUser.getId(), PageResponse.pageable(page, size));
        return PageResponse.from(businesses.map(BusinessResponse::from));
    }

    // @PathVariable: pulls the {id} segment out of the URL into this parameter.
    // ResponseEntity<T> lets us choose the actual HTTP status code returned (200 vs 404),
    // instead of Spring always defaulting to 200.
    //
    // findByIdAndOwnerId (not findById) is what actually enforces ownership here - a business
    // that exists but belongs to a different user returns empty, same 404 as if it didn't
    // exist at all. Deliberately not a 403: revealing "this ID exists, it's just not yours"
    // leaks more than a plain "not found" does.
    @GetMapping("/{id}/deadlines")
    public ResponseEntity<?> getDeadlines(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        List<WorkPass> workPasses = workPassRepository.findByBusinessId(id);
        List<CustomObligation> customObligations = customObligationRepository.findByBusinessId(id);
        List<Deadline> deadlines = ruleEngine.computeDeadlines(
                business.get(), workPasses, customObligations, LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE));

        // Issue #34: a deadline the user has manually dismissed is filtered out of the live view
        // here, not inside RuleEngine itself - RuleEngine stays a pure computation with no notion
        // of state (see DismissedDeadlineKey's own comment).
        Set<DismissedDeadlineKey> dismissed = dismissedDeadlineRepository.findByBusinessId(id).stream()
                .map(DismissedDeadlineKey::of)
                .collect(Collectors.toSet());
        List<Deadline> visible = deadlines.stream()
                .filter(d -> !dismissed.contains(DismissedDeadlineKey.of(id, d)))
                .toList();

        return ResponseEntity.ok(visible);
    }

    // Issue #57: every DeadlineRecord this app has ever persisted for the business, past and
    // future - the real historical audit trail. Distinct from getDeadlines above, which only
    // ever shows a recurring obligation's *next* live-computed occurrence; a past year's ACRA
    // filing has no other way to still be visible once RuleEngine has moved on to computing
    // next year's. Paginated (issue #49's convention) since, unlike the live view, this table
    // only grows over time.
    @GetMapping("/{id}/deadlines/history")
    public ResponseEntity<?> getDeadlineHistory(@PathVariable Long id, @AuthenticationPrincipal User currentUser,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(id, currentUser.getId());
        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Set<DismissedDeadlineKey> dismissed = dismissedDeadlineRepository.findByBusinessId(id).stream()
                .map(DismissedDeadlineKey::of)
                .collect(Collectors.toSet());

        Page<DeadlineRecord> records = deadlineRecordRepository.findByBusinessIdOrderByDueDateDesc(
                id, PageResponse.pageable(page, size));
        Page<DeadlineHistoryEntryResponse> history = records.map(record ->
                DeadlineHistoryEntryResponse.from(record, dismissed.contains(DismissedDeadlineKey.of(id, record))));

        return ResponseEntity.ok(PageResponse.from(history));
    }

    // Applies a BusinessRequest's fields onto the already-owned, already-persisted entity
    // fetched via findByIdAndOwnerId - same structural IDOR-avoidance as createBusiness above,
    // there's no id/owner field on the request DTO for a client to even supply.
    //
    // @Transactional: deleteByBusinessIdAndObligationTypeAndReminderSentFalse below is a derived
    // delete query, which (like PasswordResetTokenRepository.deleteByUserId, issue #37) needs an
    // actual transaction already open on the calling thread - a plain repository call doesn't
    // provide one by default.
    @Transactional
    @PutMapping("/{id}")
    public ResponseEntity<?> updateBusiness(@PathVariable Long id, @Valid @RequestBody BusinessRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        Optional<Business> existing = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Business business = existing.get();

        // Deliberately NOT re-running exceedsFirstYearAcraLimit here, unlike createBusiness -
        // that check only means something at the moment a first FYE is actually being declared
        // for a brand new business. Once a business exists, its financialYearEnd could
        // legitimately represent any later, unrelated annual cycle - re-validating it against
        // incorporationDate on every future edit would risk flagging a perfectly normal update
        // to a long-standing business as a "first year" violation it has nothing to do with.
        // Changing an existing FYE has its own (different, ≤12-months-normally, ≤18-with-
        // approval) validation rule, not modeled yet - a real, deliberately separate scope
        // decision from the stale-record cleanup below (issue #30's own scope).
        //
        // The actual bug #30 exists for: DeadlineSyncService recomputes deadlines from
        // RuleEngine every day and inserts any not already persisted, but has no way to remove
        // one that's now WRONG because the FYE it was computed from just changed - its own
        // dedupe check only prevents re-inserting something that's already correct. Without this
        // cleanup, a business that changes FYE would end up with the stale, unreminded old ACRA
        // deadline still sitting in the queue right alongside the newly-synced correct one, and
        // could get reminded off the wrong due date.
        boolean financialYearEndChanged = !Objects.equals(business.getFinancialYearEnd(), request.financialYearEnd());
        // Issue #45 - a changed filing frequency changes what the *next* GST F5 accounting
        // period end (and so due date) actually is, the same #30-style staleness FYE changes
        // already needed handling for. Only meaningful to compare when a value is actually
        // supplied - an omitted gstFilingFrequency preserves the existing one below, and that
        // path is never a "change" at all.
        boolean gstFilingFrequencyChanged = request.gstFilingFrequency() != null
                && !Objects.equals(business.getGstFilingFrequency(), request.gstFilingFrequency());

        business.setName(request.name());
        business.setFinancialYearEnd(request.financialYearEnd());
        business.setGstRegistered(request.gstRegistered());
        // Unlike createBusiness's default-to-14, an omitted leadTimeDays here leaves the
        // existing value untouched rather than resetting it - the current frontend doesn't send
        // this field yet, and a PUT from it must not silently wipe out a lead time the user
        // previously customized through some other client (e.g. a direct API call).
        if (request.leadTimeDays() != null) {
            business.setLeadTimeDays(request.leadTimeDays());
        }
        // Same preserve-if-omitted pattern as leadTimeDays, and for the same reason.
        if (request.incorporationDate() != null) {
            business.setIncorporationDate(request.incorporationDate());
        }
        // Same preserve-if-omitted pattern again (issue #45).
        if (request.gstFilingFrequency() != null) {
            business.setGstFilingFrequency(request.gstFilingFrequency());
        }

        Business saved = businessRepository.save(business);

        if (financialYearEndChanged) {
            deadlineRecordRepository.deleteByBusinessIdAndObligationTypeAndReminderSentFalse(
                    saved.getId(), ObligationType.ACRA_ANNUAL_RETURN);
        }
        if (gstFilingFrequencyChanged) {
            deadlineRecordRepository.deleteByBusinessIdAndObligationTypeAndReminderSentFalse(
                    saved.getId(), ObligationType.GST_F5);
        }

        return ResponseEntity.ok(BusinessResponse.from(saved));
    }

    // Deleting a business also removes its work passes and deadline records - enforced via
    // ON DELETE CASCADE at the DB level (see V3 migration), not by this method issuing separate
    // deletes, so it stays correct regardless of how a business row is ever removed.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBusiness(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (business.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        businessRepository.delete(business.get());
        return ResponseEntity.noContent().build();
    }
}
