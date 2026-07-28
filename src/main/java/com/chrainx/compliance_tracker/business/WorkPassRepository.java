package com.chrainx.compliance_tracker.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkPassRepository extends JpaRepository<WorkPass, Long> {

    // Spring Data JPA reads this method name and generates the query itself -
    // "findByBusinessId" becomes "SELECT * FROM work_pass WHERE business_id = ?".
    // No SQL or method body needed, same free-implementation trick as JpaRepository itself.
    //
    // Kept alongside the paginated overload below (issue #49), not replaced by it - two
    // internal callers (BusinessController.getDeadlines, DeadlineSyncService.syncDeadlines) need
    // every work pass to compute deadlines correctly, not just one page of them. Only
    // WorkPassController's own public listing endpoint should ever paginate.
    List<WorkPass> findByBusinessId(Long businessId);

    Page<WorkPass> findByBusinessId(Long businessId, Pageable pageable);

    // Same ownership-scoping idea as BusinessRepository.findByIdAndOwnerId - a work pass that
    // exists but belongs to a different business (and therefore a different owner, since the
    // controller already checked business ownership before calling this) should be
    // indistinguishable from one that doesn't exist at all.
    Optional<WorkPass> findByIdAndBusinessId(Long id, Long businessId);
}
