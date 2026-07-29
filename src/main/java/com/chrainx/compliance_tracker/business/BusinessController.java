package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// @RestController: marks this class as an HTTP handler whose return values get serialized
// straight to JSON, instead of being treated as a view template name.
// @RequestMapping: base path prefix shared by every method below.
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessRepository businessRepository;
    private final WorkPassRepository workPassRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final RuleEngine ruleEngine;

    // @Autowired: Spring sees this constructor needs a BusinessRepository, WorkPassRepository,
    // IdempotencyKeyRepository, and RuleEngine, and since it already knows how to create all
    // four (repositories are auto-implemented interfaces, RuleEngine is @Component), it builds
    // them and passes them in automatically - we never call `new BusinessController(...)`
    // ourselves.
    @Autowired
    public BusinessController(BusinessRepository businessRepository, WorkPassRepository workPassRepository,
                               IdempotencyKeyRepository idempotencyKeyRepository, RuleEngine ruleEngine) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
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
    public BusinessResponse createBusiness(@Valid @RequestBody BusinessRequest request,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                            @AuthenticationPrincipal User currentUser) {
        if (idempotencyKey != null) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKeyAndOwnerId(idempotencyKey, currentUser.getId());
            if (existing.isPresent()) {
                return BusinessResponse.from(businessRepository.findById(existing.get().getBusinessId()).orElseThrow());
            }
        }

        Business business = new Business();
        business.setName(request.name());
        business.setFinancialYearEnd(request.financialYearEnd());
        business.setGstRegistered(request.gstRegistered());
        business.setOwner(currentUser);
        business = businessRepository.save(business);

        if (idempotencyKey != null) {
            business = claimIdempotencyKeyOrReturnTheWinners(idempotencyKey, currentUser.getId(), business);
        }

        return BusinessResponse.from(business);
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
        List<Deadline> deadlines = ruleEngine.computeDeadlines(business.get(), workPasses, LocalDate.now(RuleEngine.SINGAPORE_TIME_ZONE));
        return ResponseEntity.ok(deadlines);
    }

    // Applies a BusinessRequest's fields onto the already-owned, already-persisted entity
    // fetched via findByIdAndOwnerId - same structural IDOR-avoidance as createBusiness above,
    // there's no id/owner field on the request DTO for a client to even supply.
    @PutMapping("/{id}")
    public ResponseEntity<?> updateBusiness(@PathVariable Long id, @Valid @RequestBody BusinessRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        Optional<Business> existing = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Business not found."));
        }

        Business business = existing.get();
        business.setName(request.name());
        business.setFinancialYearEnd(request.financialYearEnd());
        business.setGstRegistered(request.gstRegistered());

        return ResponseEntity.ok(BusinessResponse.from(businessRepository.save(business)));
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
