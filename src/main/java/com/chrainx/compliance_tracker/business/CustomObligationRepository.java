package com.chrainx.compliance_tracker.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomObligationRepository extends JpaRepository<CustomObligation, Long> {

    // Same reasoning as WorkPassRepository's equivalent pair - the unpaginated overload is for
    // internal callers (RuleEngine via BusinessController/DeadlineSyncService) that need every
    // custom obligation to compute deadlines correctly, not just one page of them.
    List<CustomObligation> findByBusinessId(Long businessId);

    Page<CustomObligation> findByBusinessId(Long businessId, Pageable pageable);

    Optional<CustomObligation> findByIdAndBusinessId(Long id, Long businessId);
}
