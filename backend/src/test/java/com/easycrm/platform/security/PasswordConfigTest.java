package com.easycrm.platform.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {
    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void hashesAndMatches() {
        String hash = encoder.encode("s3cret-pass");
        assertNotEquals("s3cret-pass", hash, "must not store plaintext");
        assertTrue(encoder.matches("s3cret-pass", hash));
        assertFalse(encoder.matches("wrong", hash));
    }

    @Test
    void isBcrypt() {
        assertTrue(encoder.encode("x").startsWith("$2"), "bcrypt hashes start with $2");
    }
}
