package com.chrainx.compliance_tracker.business;
import com.chrainx.compliance_tracker.auth.User;

import com.chrainx.compliance_tracker.error.ApiError;
import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.RuleEngine;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RuleEngine ruleEngine;

    // @Autowired: Spring sees this constructor needs a BusinessRepository, WorkPassRepository,
    // and RuleEngine, and since it already knows how to create all three (repositories are
    // auto-implemented interfaces, RuleEngine is @Component), it builds them and passes them
    // in automatically - we never call `new BusinessController(...)` ourselves.
    @Autowired
    public BusinessController(BusinessRepository businessRepository, WorkPassRepository workPassRepository, RuleEngine ruleEngine) {
        this.businessRepository = businessRepository;
        this.workPassRepository = workPassRepository;
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
    @PostMapping
    public BusinessResponse createBusiness(@Valid @RequestBody BusinessRequest request, @AuthenticationPrincipal User currentUser) {
        Business business = new Business();
        business.setName(request.name());
        business.setFinancialYearEnd(request.financialYearEnd());
        business.setGstRegistered(request.gstRegistered());
        business.setOwner(currentUser);
        return BusinessResponse.from(businessRepository.save(business));
    }

    @GetMapping
    public List<BusinessResponse> getAllBusinesses(@AuthenticationPrincipal User currentUser) {
        return businessRepository.findByOwnerId(currentUser.getId()).stream().map(BusinessResponse::from).toList();
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
