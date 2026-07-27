package com.chrainx.compliance_tracker;

import com.chrainx.compliance_tracker.rules.Deadline;
import com.chrainx.compliance_tracker.rules.RuleEngine;
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
    @PostMapping
    public Business createBusiness(@RequestBody Business business, @AuthenticationPrincipal User currentUser) {
        // business is bound straight from the request body, so a caller can supply an "id"
        // field of their own choosing. JPA's save() does an UPDATE (not an INSERT) whenever
        // the entity's id is non-null and already exists in the table - so without this,
        // any authenticated user could overwrite (and take ownership of) another user's
        // existing business just by guessing/incrementing its id. Force a fresh insert.
        business.setId(null);
        business.setOwner(currentUser);
        return businessRepository.save(business);
    }

    @GetMapping
    public List<Business> getAllBusinesses(@AuthenticationPrincipal User currentUser) {
        return businessRepository.findByOwnerId(currentUser.getId());
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
    public ResponseEntity<List<Deadline>> getDeadlines(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (business.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<WorkPass> workPasses = workPassRepository.findByBusinessId(id);
        List<Deadline> deadlines = ruleEngine.computeDeadlines(business.get(), workPasses, LocalDate.now());
        return ResponseEntity.ok(deadlines);
    }

    // Deliberately never saves the client-supplied `updates` object directly - only copies its
    // mutable fields (name/financialYearEnd/gstRegistered) onto the already-owned, already-
    // persisted entity fetched via findByIdAndOwnerId. Same IDOR-avoidance shape as #66's fix:
    // id and owner are never touched by anything the client sent, so there's no id/owner field
    // to even accidentally trust.
    @PutMapping("/{id}")
    public ResponseEntity<Business> updateBusiness(@PathVariable Long id, @RequestBody Business updates,
                                                    @AuthenticationPrincipal User currentUser) {
        Optional<Business> existing = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Business business = existing.get();
        business.setName(updates.getName());
        business.setFinancialYearEnd(updates.getFinancialYearEnd());
        business.setGstRegistered(updates.isGstRegistered());

        return ResponseEntity.ok(businessRepository.save(business));
    }

    // Deleting a business also removes its work passes and deadline records - enforced via
    // ON DELETE CASCADE at the DB level (see V3 migration), not by this method issuing separate
    // deletes, so it stays correct regardless of how a business row is ever removed.
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBusiness(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        Optional<Business> business = businessRepository.findByIdAndOwnerId(id, currentUser.getId());

        if (business.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        businessRepository.delete(business.get());
        return ResponseEntity.noContent().build();
    }
}
