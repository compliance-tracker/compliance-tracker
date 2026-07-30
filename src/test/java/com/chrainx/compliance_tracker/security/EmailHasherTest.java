package com.chrainx.compliance_tracker.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EmailHasherTest {

    private final EmailHasher hasher = new EmailHasher("I9FNAHshRkw+oPgsfjRlvm+F3SNRE30qlcWwcY5Tn7A=");

    @Test
    void hashingTheSameEmailTwice_producesTheSameHash() {
        // Deliberately deterministic, unlike EncryptedStringConverter - this is the whole point,
        // it's what lets User.findByEmailHash actually find anything.
        assertEquals(hasher.hash("owner@example.com"), hasher.hash("owner@example.com"));
    }

    @Test
    void differentEmails_produceDifferentHashes() {
        assertNotEquals(hasher.hash("owner@example.com"), hasher.hash("someone-else@example.com"));
    }

    @Test
    void hash_isNeverTheRawEmail() {
        assertNotEquals("owner@example.com", hasher.hash("owner@example.com"));
    }

    @Test
    void hashingWithADifferentKey_producesADifferentHash() {
        // A real proof this is a *keyed* hash (HMAC), not a plain unsalted digest - an attacker
        // with the database but not app.encryption-key can't build a rainbow table against it.
        EmailHasher differentKeyHasher = new EmailHasher("9R0DzcnMWpajKS36QUFAtytGdUoHmzCY1MpVIYU4Kp8=");

        assertNotEquals(hasher.hash("owner@example.com"), differentKeyHasher.hash("owner@example.com"));
    }
}
