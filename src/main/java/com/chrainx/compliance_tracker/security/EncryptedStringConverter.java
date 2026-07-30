package com.chrainx.compliance_tracker.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

// Issue #63: transparent column-level encryption for PII fields (User.email,
// Business.name, WorkPass.employeeName) - applied via @Convert(converter =
// EncryptedStringConverter.class) on the entity field, so every other line of application code
// that reads/writes these fields is completely unaware encryption is happening at all.
//
// @Component (not a plain @Converter-only class): Spring Boot's auto-configured
// EntityManagerFactory registers hibernate.resource.beans.container =
// org.springframework.orm.jpa.SpringBeanContainer automatically, which lets Hibernate resolve
// a @Converter class through the real Spring application context instead of raw reflection -
// that's what makes @Value injection below actually work. Verified live, not assumed, given
// this project's history of real Spring Boot behavior surprises.
//
// AES-256-GCM: authenticated encryption (detects tampering, not just confidentiality) - the
// standard modern choice, and deliberately non-deterministic (a fresh random 12-byte IV every
// single encryption, even for the exact same plaintext twice in a row) so encrypted values never
// leak equality/pattern information by comparison of ciphertext alone. That's precisely why
// User.email can't rely on this column for exact-match lookup - see EmailHasher's own comment
// for the separate deterministic mechanism that exists for that.
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;

    @Autowired
    public EncryptedStringConverter(@Value("${app.encryption-key}") String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            // IV prepended to the ciphertext+tag, since decryption needs the exact same IV back
            // and there's nowhere else to store it - a fresh column per encrypted field just to
            // hold a 12-byte IV would be real schema overhead for no benefit, the IV isn't secret.
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            // Every failure mode here (bad key length, unsupported algorithm) is a genuine
            // programming/config error, not something a caller can recover from - same
            // unchecked-wrapping reasoning as MessagingException elsewhere in this codebase.
            throw new IllegalStateException("Failed to encrypt column value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Reachable for real, not just in theory: the wrong key (e.g. app.encryption-key
            // rotated without re-encrypting existing rows) fails GCM's authentication tag check
            // here, not silently - a corrupted/tampered/wrong-key row throws instead of
            // returning garbage plaintext.
            throw new IllegalStateException("Failed to decrypt column value", e);
        }
    }
}
