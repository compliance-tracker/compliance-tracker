package com.chrainx.compliance_tracker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkPassRepository extends JpaRepository<WorkPass, Long> {

    // Spring Data JPA reads this method name and generates the query itself -
    // "findByBusinessId" becomes "SELECT * FROM work_pass WHERE business_id = ?".
    // No SQL or method body needed, same free-implementation trick as JpaRepository itself.
    List<WorkPass> findByBusinessId(Long businessId);

    // Same ownership-scoping idea as BusinessRepository.findByIdAndOwnerId - a work pass that
    // exists but belongs to a different business (and therefore a different owner, since the
    // controller already checked business ownership before calling this) should be
    // indistinguishable from one that doesn't exist at all.
    Optional<WorkPass> findByIdAndBusinessId(Long id, Long businessId);
}
