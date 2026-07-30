package com.chrainx.compliance_tracker.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    // Ownership-scoped queries - a logged-in user should only ever see their own businesses,
    // never anyone else's. findAll()/findById() (inherited, still used by DeadlineSyncService
    // for the background reminder pipeline) deliberately stay unscoped, since that job needs to
    // process every business's deadlines regardless of owner.
    //
    // Page<Business>, not List<Business> (issue #49) - GET /api/businesses is the only caller,
    // and returning everything unpaginated stops scaling once an account has hundreds of
    // businesses (e.g. an accounting firm managing many clients).
    Page<Business> findByOwnerId(Long ownerId, Pageable pageable);

    // Unpaginated counterpart (issue #48) - AuthController's account data export needs every
    // business a user owns, same "internal caller needs everything, not just one page" reasoning
    // WorkPassRepository/CustomObligationRepository's equivalent overloads already established.
    List<Business> findByOwnerId(Long ownerId);

    Optional<Business> findByIdAndOwnerId(Long id, Long ownerId);
}
