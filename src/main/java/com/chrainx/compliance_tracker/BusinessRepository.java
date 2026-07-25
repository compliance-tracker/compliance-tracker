package com.chrainx.compliance_tracker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    // Ownership-scoped queries - a logged-in user should only ever see their own businesses,
    // never anyone else's. findAll()/findById() (inherited, still used by DeadlineSyncService
    // for the background reminder pipeline) deliberately stay unscoped, since that job needs to
    // process every business's deadlines regardless of owner.
    List<Business> findByOwnerId(Long ownerId);

    Optional<Business> findByIdAndOwnerId(Long id, Long ownerId);
}
