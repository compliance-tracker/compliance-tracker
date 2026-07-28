package com.chrainx.compliance_tracker.auth;

import jakarta.persistence.*;

import java.time.Instant;

// A single-use, expiring token generated on registration and consumed by verify-email (issue
// #36) - same shape as PasswordResetToken (issue #37), deliberately its own table for the same
// reason: a user has zero or one of these at a time, and it's a fundamentally different kind of
// "credential" than the password hash.
@Entity
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String token;

    private Long userId;

    private Instant expiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
