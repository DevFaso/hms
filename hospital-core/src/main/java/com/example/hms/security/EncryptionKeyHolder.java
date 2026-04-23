package com.example.hms.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the symmetric AES-256 key used by {@link EncryptedStringConverter}.
 *
 * <p>The key is sourced from the {@code app.encryption.key} property, which
 * MUST be a Base64-encoded 32-byte value supplied via env var (never
 * committed). The key is loaded once at startup and exposed via a static
 * accessor so that JPA {@code AttributeConverter} instances — which
 * Hibernate constructs without Spring injection — can reach it.
 *
 * <p>If the property is missing or empty, the holder leaves the key unset.
 * Any subsequent encryption attempt fails fast with a descriptive error.
 * Decryption of a value with no version prefix is treated as legacy
 * plaintext and passed through unchanged so an in-place migration can roll
 * out one entity at a time.
 *
 * <p>Security task S-05.
 */
@Component
public class EncryptionKeyHolder {

    /** Base64-encoded 256-bit (32 byte) AES key, or empty string if not configured. */
    private final String configuredKey;

    /**
     * Thread-safe holder for the loaded AES key. Used through an
     * {@link AtomicReference} so the static accessor contract is visible to
     * JPA {@code AttributeConverter} instances across threads without
     * relying on bare {@code volatile} fields.
     */
    private static final AtomicReference<SecretKey> KEY_REF = new AtomicReference<>();

    public EncryptionKeyHolder(@Value("${app.encryption.key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    @PostConstruct
    void init() {
        if (configuredKey.isEmpty()) {
            KEY_REF.set(null);
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "app.encryption.key is not valid Base64", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "app.encryption.key must decode to exactly 32 bytes (256 bits); got "
                            + decoded.length);
        }
        KEY_REF.set(new SecretKeySpec(decoded, "AES"));
    }

    /** @return the loaded AES key, or {@code null} if the property was empty. */
    public static SecretKey getKey() {
        return KEY_REF.get();
    }

    /** @return {@code true} when an encryption key has been configured. */
    public static boolean isConfigured() {
        return KEY_REF.get() != null;
    }

    /**
     * Test-only hook to install a key without going through Spring lifecycle.
     * Production code should always rely on {@link #init()}.
     */
    static void setKeyForTesting(SecretKey key) {
        KEY_REF.set(key);
    }

    /**
     * Test-only accessor for snapshot/restore patterns. Returns the key
     * currently held in the static slot (which may be a Spring-managed key
     * installed by {@link #init()} or a previously-installed test key).
     */
    static SecretKey getKeyForTesting() {
        return KEY_REF.get();
    }
}
