package com.chrainx.compliance_tracker.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // Issue #63: email is encrypted at rest (non-deterministically - see
    // EncryptedStringConverter), so a derived findByEmail would compare a plaintext argument
    // against ciphertext and never match. Every real lookup goes through the deterministic
    // emailHash instead (see EmailHasher) - every call site computes the hash itself
    // (emailHasher.hash(email)) and passes it here, rather than this method silently hashing
    // internally, so it's always obvious at the call site which value is actually being queried.
    Optional<User> findByEmailHash(String emailHash);
}
