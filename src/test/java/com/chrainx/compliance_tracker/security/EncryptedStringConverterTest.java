package com.chrainx.compliance_tracker.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedStringConverterTest {

    // Any valid Base64-encoded 32-byte (256-bit) value works - not the app's real key.
    private final EncryptedStringConverter converter =
            new EncryptedStringConverter("I9FNAHshRkw+oPgsfjRlvm+F3SNRE30qlcWwcY5Tn7A=");

    @Test
    void roundTrips_backToTheOriginalPlaintext() {
        String encrypted = converter.convertToDatabaseColumn("owner@example.com");

        assertEquals("owner@example.com", converter.convertToEntityAttribute(encrypted));
    }

    @Test
    void encryptedValue_neverContainsTheRawPlaintext() {
        // The actual point of this issue - proof the column really is encrypted, not just
        // base64'd or otherwise trivially reversible without the key.
        String encrypted = converter.convertToDatabaseColumn("owner@example.com");

        assertTrue(!encrypted.contains("owner") && !encrypted.contains("example.com"));
    }

    @Test
    void encryptingTheSamePlaintextTwice_producesDifferentCiphertext() {
        // Deliberately non-deterministic (a fresh random IV every time) - this is exactly why
        // User.email needs a separate deterministic lookup hash (EmailHasher) instead of relying
        // on this column for exact-match queries.
        String first = converter.convertToDatabaseColumn("owner@example.com");
        String second = converter.convertToDatabaseColumn("owner@example.com");

        assertNotEquals(first, second);
        assertEquals("owner@example.com", converter.convertToEntityAttribute(first));
        assertEquals("owner@example.com", converter.convertToEntityAttribute(second));
    }

    @Test
    void nullIn_producesNullOut_bothDirections() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }

    @Test
    void decryptingWithADifferentKey_failsLoudly_notWithGarbagePlaintext() {
        // GCM's authentication tag check must catch a wrong key, not silently return garbage -
        // real proof that this is authenticated encryption, not just confidentiality.
        String encrypted = converter.convertToDatabaseColumn("owner@example.com");
        EncryptedStringConverter differentKeyConverter =
                new EncryptedStringConverter("9R0DzcnMWpajKS36QUFAtytGdUoHmzCY1MpVIYU4Kp8=");

        assertThrows(IllegalStateException.class, () -> differentKeyConverter.convertToEntityAttribute(encrypted));
    }
}
