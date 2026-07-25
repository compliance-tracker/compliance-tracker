package com.chrainx.compliance_tracker;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
