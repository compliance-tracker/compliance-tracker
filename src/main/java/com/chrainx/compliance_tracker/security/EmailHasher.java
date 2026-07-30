package com.chrainx.compliance_tracker.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

// Issue #63: User.email is encrypted at rest (see EncryptedStringConverter), but
// EncryptedStringConverter is deliberately non-deterministic - a fresh random IV every time, so
// the same email produces different ciphertext on every save. That's exactly right for
// confidentiality, and exactly wrong for User.findByEmail (login, register's uniqueness check,
// password reset, etc. all need an exact-match lookup) or the DB's own UNIQUE constraint, which
// used to sit directly on the email column itself and would silently become meaningless once
// email stopped being comparable by equality.
//
// The fix: User.emailHash is a separate, deterministic HMAC-SHA256 of the raw email (same key
// as encryption, different algorithm - not the same key used for the same purpose twice), stored
// alongside the encrypted email and given the UNIQUE constraint the encrypted column can no
// longer meaningfully carry. Every lookup goes through UserRepository.findByEmailHash(hash(...))
// instead of a derived findByEmail. HMAC (keyed), not a plain unsalted hash - an attacker who
// somehow obtained the database still can't build a rainbow table against emailHash without
// also having app.encryption-key.
@Component
public class EmailHasher {

    private final SecretKeySpec key;

    @Autowired
    public EmailHasher(@Value("${app.encryption-key}") String base64Key) {
        this.key = new SecretKeySpec(java.util.Base64.getDecoder().decode(base64Key), "HmacSHA256");
    }

    public String hash(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(email.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to hash email", e);
        }
    }
}
