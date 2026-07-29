package com.chrainx.compliance_tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    // Called both when issuing a fresh token (so a user who requests multiple resets only ever
    // has the latest one valid, instead of accumulating unused ones) and after a successful
    // reset (so the just-used token - and any other stray ones - can't be reused).
    //
    // A bulk JPQL delete (issue #115), not the derived-method default - see
    // EmailVerificationTokenRepository.deleteByUserId for why, including why this returns the
    // affected-row count instead of void.
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
