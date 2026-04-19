package com.example.hms.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA AttributeConverter that encrypts/decrypts TOTP secrets at rest using AES-256-GCM.
 * <p>
 * The encrypted value is stored as a Base64 string: {@code ENC:<base64(iv + ciphertext + tag)>}.
 * <p>
 * Configure via {@code app.security.totp-encryption-key} — a 32-byte hex-encoded key.
 */
@Slf4j
@Component
@Converter
public class TotpSecretEncryptor implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String PREFIX = "ENC:";

    private final SecretKeySpec keySpec;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpSecretEncryptor(
            @Value("${app.security.totp-encryption-key}")
            String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            throw new IllegalStateException(
                    "app.security.totp-encryption-key must be configured. "
                  + "Generate a 32-byte hex key: openssl rand -hex 32");
        }
        byte[] keyBytes = hexStringToBytes(hexKey);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("TOTP encryption key must be 32 bytes (64 hex chars)");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt TOTP secret", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        // Support reading plaintext secrets during migration
        if (!dbData.startsWith(PREFIX)) {
            return dbData;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt TOTP secret", e);
        }
    }

    private static byte[] hexStringToBytes(String hex) {
        String s = hex.trim();
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "TOTP encryption key hex string must have even length, got " + s.length());
        }
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException(
                        "TOTP encryption key contains invalid hex character at position " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
