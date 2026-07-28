package com.chrainx.compliance_tracker.auth;

import jakarta.persistence.*;

// Table name is "app_user", not "user" - "user" is a reserved word in Postgres (and most SQL
// dialects), would need quoting everywhere otherwise.
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    // Never the plain-text password - always the output of BCrypt hashing (see AuthController).
    @Column(nullable = false)
    private String passwordHash;

    // Set once the user confirms ownership of their email via the verify-email flow (issue #36).
    // Deliberately informational only right now - nothing in the app currently checks this or
    // blocks an unverified account from doing anything; register still returns real, usable
    // tokens immediately. Enforcing/gating on it is a natural, separate follow-up, not assumed
    // to be part of this flag's introduction.
    private boolean emailVerified = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
}
