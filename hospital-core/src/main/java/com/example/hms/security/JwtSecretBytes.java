package com.example.hms.security;

import com.example.hms.bootstrap.StartupConfigurationException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;

import java.nio.charset.StandardCharsets;

/**
 * The key bytes an {@code app.jwt.secret} value stands for: {@code base64:<value>},
 * {@code hex:<value>}, or the plain text's UTF-8 bytes. One decoder for the token provider that
 * signs with the key and the start-up validator that judges it, so both see the same key.
 */
public final class JwtSecretBytes {

    public static final String BASE64_PREFIX = "base64:";
    public static final String HEX_PREFIX = "hex:";

    private JwtSecretBytes() {
    }

    /** The key bytes of {@code value}; a malformed base64 or hex value stops the start-up. */
    public static byte[] decode(String value) {
        if (value.startsWith(BASE64_PREFIX)) {
            try {
                return Decoders.BASE64.decode(value.substring(BASE64_PREFIX.length()));
            } catch (DecodingException ex) {
                throw new StartupConfigurationException("The base64 JWT secret cannot be decoded");
            }
        }
        if (value.startsWith(HEX_PREFIX)) {
            return hexToBytes(value.substring(HEX_PREFIX.length()));
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        String s = hex.trim();
        if ((s.length() & 1) != 0) {
            throw new StartupConfigurationException("Hex-encoded secret must have even length");
        }
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new StartupConfigurationException("Invalid hex character in secret");
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
