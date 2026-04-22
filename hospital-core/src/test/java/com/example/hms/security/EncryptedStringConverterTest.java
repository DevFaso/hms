package com.example.hms.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EncryptedStringConverter} — security task S-05.
 *
 * <p>These tests intentionally drive the converter through its public
 * JPA contract (the two {@code convertTo*} methods) so they catch any
 * regression that would corrupt PHI columns at rest.
 */
class EncryptedStringConverterTest {

    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        EncryptionKeyHolder.setKeyForTesting(gen.generateKey());
        converter = new EncryptedStringConverter();
    }

    @AfterEach
    void tearDown() {
        EncryptionKeyHolder.setKeyForTesting(null);
    }

    @Test
    void roundTrip_preservesOriginalValue() {
        String original = "John Q. Patient — DOB 1980-01-15, MRN 0001234";
        String encrypted = converter.convertToDatabaseColumn(original);

        assertThat(encrypted).isNotEqualTo(original);
        assertThat(encrypted).startsWith("gcm1:");
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(original);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachCall_dueToRandomIv() {
        String original = "same-input-every-time";
        Set<String> samples = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            samples.add(converter.convertToDatabaseColumn(original));
        }
        // 10 encryptions of the same plaintext must yield 10 distinct ciphertexts
        assertThat(samples).hasSize(10);
        for (String s : samples) {
            assertThat(converter.convertToEntityAttribute(s)).isEqualTo(original);
        }
    }

    @Test
    void null_isPassedThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void empty_isPassedThroughWithoutEncryption() {
        assertThat(converter.convertToDatabaseColumn("")).isEqualTo("");
        assertThat(converter.convertToEntityAttribute("")).isEqualTo("");
    }

    @Test
    void legacyPlaintext_withoutVersionPrefix_isReturnedAsIs() {
        // Simulates a row written before encryption was enabled.
        String legacy = "plaintext-from-before-S05";
        assertThat(converter.convertToEntityAttribute(legacy)).isEqualTo(legacy);
    }

    @Test
    void tamperedCiphertext_throws() {
        String original = "sensitive-mrn-12345";
        String encrypted = converter.convertToDatabaseColumn(original);

        // Flip the last character of the base64 payload to invalidate the GCM tag.
        char last = encrypted.charAt(encrypted.length() - 1);
        char tampered = (last == 'A') ? 'B' : 'A';
        String corrupted = encrypted.substring(0, encrypted.length() - 1) + tampered;

        assertThatThrownBy(() -> converter.convertToEntityAttribute(corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    void truncatedCiphertext_throws() {
        // "gcm1:" + base64 of 12 bytes (only IV, no actual ciphertext)
        String tooShort = "gcm1:" + Base64.getEncoder().encodeToString(new byte[12]);
        assertThatThrownBy(() -> converter.convertToEntityAttribute(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKey_failsFastOnEncrypt() {
        EncryptionKeyHolder.setKeyForTesting(null);
        assertThatThrownBy(() -> converter.convertToDatabaseColumn("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.encryption.key");
    }

    @Test
    void missingKey_failsFastOnDecryptOfEncryptedValue() {
        // Produce ciphertext while the key is set, then yank the key.
        String encrypted = converter.convertToDatabaseColumn("data");
        EncryptionKeyHolder.setKeyForTesting(null);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.encryption.key");
    }

    @Test
    void missingKey_doesNotBlockNullOrEmptyOrLegacyReads() {
        EncryptionKeyHolder.setKeyForTesting(null);
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn("")).isEqualTo("");
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isEqualTo("");
        assertThat(converter.convertToEntityAttribute("legacy-plaintext"))
                .isEqualTo("legacy-plaintext");
    }

    @Test
    void unicode_isPreservedThroughRoundTrip() {
        String original = "Patient: 李明 — diagnóstico: febrícula 38.5°C ✓";
        String encrypted = converter.convertToDatabaseColumn(original);
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(original);
    }

    @Test
    void wrongKey_failsAuthenticationTag() {
        String original = "phi-payload";
        String encrypted = converter.convertToDatabaseColumn(original);

        // Replace the key with a different random one.
        SecretKey otherKey = new SecretKeySpec(new byte[32], "AES");
        EncryptionKeyHolder.setKeyForTesting(otherKey);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }
}
