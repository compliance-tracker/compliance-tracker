package com.chrainx.compliance_tracker.auth;

import jakarta.persistence.*;

import java.time.Instant;

// A single-use, expiring token generated on "forgot password" and consumed on "reset password"
// (issue #37). Deliberately its own table rather than a column on User - a user normally has
// zero of these, briefly has one after requesting a reset, and it's a fundamentally different
// kind of "credential" than the password hash itself (short-lived, single-purpose, not meant to
// authenticate anything beyond "prove you received this specific email").
@Entity
public class PasswordResetToken {

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
