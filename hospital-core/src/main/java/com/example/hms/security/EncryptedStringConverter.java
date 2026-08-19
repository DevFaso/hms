package com.example.hms.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA {@link AttributeConverter} that transparently encrypts {@link String}
 * columns at rest using AES-256-GCM with per-row random IVs.
 *
 * <h2>Wire format</h2>
 * Encrypted values are stored as:
 * <pre>gcm1:&lt;Base64(iv || ciphertext+tag)&gt;</pre>
 * The {@code gcm1:} version prefix lets us roll the algorithm later without
 * a destructive migration.
 *
 * <h2>Migration safety</h2>
 * <ul>
 *   <li>{@code null} ↔ {@code null}, blank string ↔ blank string — never
 *       encrypted, so empty PHI columns stay readable in DB tooling.</li>
 *   <li>On read, any value <em>without</em> the {@code gcm1:} prefix is
 *       returned verbatim, allowing legacy plaintext rows to coexist with
 *       freshly encrypted writes during a rolling migration.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * Requires {@code app.encryption.key} (Base64-encoded 32 bytes) — see
 * {@link EncryptionKeyHolder}. Apply per field using
 * {@code @Convert(converter = EncryptedStringConverter.class)} — never set
 * {@code autoApply=true} (that would silently encrypt every String column).
 *
 * <p>Security task S-05.
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** Version tag for the current encryption scheme (AES-GCM v1). */
    static final String VERSION_PREFIX = "gcm1:";

    /** GCM authentication-tag length in bits (NIST SP 800-38D). */
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** GCM IV length in bytes (NIST recommends 12 for performance + safety). */
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        if (attribute.isEmpty()) {
            return attribute;
        }
        SecretKey key = requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt PHI column", ex);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (dbData.isEmpty() || !dbData.startsWith(VERSION_PREFIX)) {
            // Legacy plaintext value written before encryption was enabled.
            return dbData;
        }
        SecretKey key = requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(dbData.substring(VERSION_PREFIX.length()));
            if (combined.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalStateException("Ciphertext shorter than IV");
            }
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt PHI column", ex);
        }
    }

    private static SecretKey requireKey() {
        SecretKey key = EncryptionKeyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "app.encryption.key is not configured — cannot encrypt/decrypt PHI columns");
        }
        return key;
    }
}
