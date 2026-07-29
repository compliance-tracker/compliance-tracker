package com.chrainx.compliance_tracker.auth;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByToken(String token);

    // A bulk JPQL delete (issue #115), not the derived-method default. The default translates to
    // loading each matching row and calling entityManager.remove() on it individually, which
    // Hibernate backs with a per-row "exactly 1 row affected" check - the exact thing that throws
    // ObjectOptimisticLockingFailureException when a concurrent request already deleted the same
    // row first. A bulk DELETE issues one plain SQL statement with no such check - and returning
    // the affected-row count (instead of void) turns "0 rows deleted" into a normal, checkable
    // value AuthController uses to tell "I genuinely just consumed this" apart from "someone else
    // already did," rather than needing to catch an exception that no longer gets thrown at all.
    @Modifying
    @Query("delete from EmailVerificationToken t where t.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
