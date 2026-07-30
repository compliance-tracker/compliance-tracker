package com.chrainx.compliance_tracker.auth;

import com.chrainx.compliance_tracker.security.EncryptedStringConverter;
import jakarta.persistence.*;

import java.time.Instant;

// Table name is "app_user", not "user" - "user" is a reserved word in Postgres (and most SQL
// dialects), would need quoting everywhere otherwise.
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Issue #63: encrypted at rest (AES-256-GCM, see EncryptedStringConverter) - no longer
    // unique at the DB level itself (encryption is deliberately non-deterministic, so the same
    // email produces different ciphertext every save; a UNIQUE constraint on this column would
    // be silently meaningless). emailHash below carries the real uniqueness guarantee and is
    // what every lookup actually queries against instead.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String email;

    // Deterministic HMAC-SHA256 of email (see EmailHasher) - exists purely so email can still be
    // looked up by exact match and enforced unique, despite the encrypted column itself no
    // longer supporting either. Computed by the caller (AuthController) whenever email is set,
    // not automatically kept in sync by this entity - there is currently no "change my email"
    // feature, so the only place this is ever computed is at registration.
    @Column(nullable = false, unique = true)
    private String emailHash;

    // Never the plain-text password - always the output of BCrypt hashing (see AuthController).
    @Column(nullable = false)
    private String passwordHash;

    // Set once the user confirms ownership of their email via the verify-email flow (issue #36).
    // Deliberately informational only right now - nothing in the app currently checks this or
    // blocks an unverified account from doing anything; register still returns real, usable
    // tokens immediately. Enforcing/gating on it is a natural, separate follow-up, not assumed
    // to be part of this flag's introduction.
    private boolean emailVerified = false;

    // Set on a successful password reset (issue #96) - any JWT issued before this instant is
    // treated as revoked (see JwtAuthenticationFilter/AuthController.refresh), even though the
    // token itself is still unexpired and correctly signed. NULL (the default for every existing
    // account, and any account that's never reset its password) means no floor at all - every
    // token stays valid until its own natural expiry, the previous behavior.
    private Instant tokenValidAfter;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getEmailHash() { return emailHash; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public Instant getTokenValidAfter() { return tokenValidAfter; }
    public void setTokenValidAfter(Instant tokenValidAfter) { this.tokenValidAfter = tokenValidAfter; }
}
