package com.chrainx.compliance_tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    // Called both when issuing a fresh token (so a user who requests multiple resets only ever
    // has the latest one valid, instead of accumulating unused ones) and after a successful
    // reset (so the just-used token - and any other stray ones - can't be reused).
    void deleteByUserId(Long userId);
}
