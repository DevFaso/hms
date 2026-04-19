package com.example.hms.controller;

import com.example.hms.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the public key(s) used for JWT verification in JWK Set format.
 * Endpoint: /.well-known/jwks.json (outside /api context path).
 *
 * When HMAC signing is active (no RSA keys configured), returns an empty key set.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtTokenProvider jwtTokenProvider;

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        List<Map<String, Object>> keys = new ArrayList<>();

        if (jwtTokenProvider.isAsymmetric()) {
            RSAPublicKey currentKey = jwtTokenProvider.getRsaPublicKey();
            if (currentKey != null) {
                keys.add(toJwk(currentKey, "current"));
            }

            RSAPublicKey previousKey = jwtTokenProvider.getPreviousRsaPublicKey();
            if (previousKey != null) {
                keys.add(toJwk(previousKey, "previous"));
            }
        }

        Map<String, Object> jwks = new LinkedHashMap<>();
        jwks.put("keys", keys);
        return ResponseEntity.ok(jwks);
    }

    private static Map<String, Object> toJwk(RSAPublicKey key, String kid) {
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", kid);
        jwk.put("n", base64UrlEncode(key.getModulus()));
        jwk.put("e", base64UrlEncode(key.getPublicExponent()));
        return jwk;
    }

    private static String base64UrlEncode(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // Remove leading zero byte if present (sign bit padding)
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
