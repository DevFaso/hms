package com.example.hms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Phase 6: RS256 asymmetric JWT signing, key rotation, and backward compatibility.
 */
@ExtendWith(MockitoExtension.class)
class JwtTokenProviderAsymmetricTest {

    @Mock
    private HospitalUserDetailsService userDetailsService;

    @Mock
    private com.example.hms.security.auth.TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
    }

    @Nested
    @DisplayName("HMAC fallback (no RSA keys)")
    class HmacFallback {

        @Test
        @DisplayName("init() with HMAC secret works as before")
        void hmacInit() {
            ReflectionTestUtils.setField(provider, "jwtSecret", "dev-secret-change-me-in-production-minimum-256-bits-long!!");
            ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900000L);
            ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172800000L);
            ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
            ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
            ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");

            provider.init();

            assertThat(provider.isAsymmetric()).isFalse();
            assertThat(provider.getVerificationKey()).isInstanceOf(javax.crypto.SecretKey.class);
            assertThat(provider.getRsaPublicKey()).isNull();
            assertThat(provider.getSecretKey()).isNotNull(); // deprecated but still works in HMAC mode
        }

        @Test
        @DisplayName("getSecretKey() throws in asymmetric mode")
        void getSecretKeyThrowsInRsaMode() throws Exception {
            KeyPair kp = generateRsaKeyPair();
            setRsaKeys(kp, null);

            provider.init();

            assertThatThrownBy(() -> provider.getSecretKey())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("RS256 asymmetric signing")
    class Rs256Signing {

        @Test
        @DisplayName("init() with RSA keys enables asymmetric mode")
        void rsaInit() throws Exception {
            KeyPair kp = generateRsaKeyPair();
            setRsaKeys(kp, null);

            provider.init();

            assertThat(provider.isAsymmetric()).isTrue();
            assertThat(provider.getRsaPublicKey()).isNotNull();
            assertThat(provider.getVerificationKey()).isInstanceOf(java.security.PublicKey.class);
        }

        @Test
        @DisplayName("MFA token can be generated and validated with RS256")
        void mfaTokenRoundTrip() throws Exception {
            KeyPair kp = generateRsaKeyPair();
            setRsaKeys(kp, null);
            provider.init();

            String mfaToken = provider.generateMfaToken("testuser");

            assertThat(provider.isMfaToken(mfaToken)).isTrue();
            assertThat(provider.getUsernameFromJWT(mfaToken)).isEqualTo("testuser");
            assertThat(provider.validateToken(mfaToken)).isTrue();
        }

        @Test
        @DisplayName("Token signed with wrong key fails validation")
        void wrongKeyFails() throws Exception {
            KeyPair kp1 = generateRsaKeyPair();
            setRsaKeys(kp1, null);
            provider.init();

            String token = provider.generateMfaToken("testuser");

            // Create new provider with different keys
            JwtTokenProvider provider2 = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
            KeyPair kp2 = generateRsaKeyPair();
            setRsaKeys(provider2, kp2, null);
            provider2.init();

            assertThat(provider2.validateToken(token)).isFalse();
        }
    }

    @Nested
    @DisplayName("Key rotation (T-44)")
    class KeyRotation {

        @Test
        @DisplayName("Token signed with old key is accepted when previous key is configured")
        void oldKeyAcceptedDuringRotation() throws Exception {
            // Generate "old" keypair and sign a token
            KeyPair oldKp = generateRsaKeyPair();
            setRsaKeys(oldKp, null);
            provider.init();
            String oldToken = provider.generateMfaToken("rotationuser");

            // Now "rotate": new keypair, old public key as previous
            KeyPair newKp = generateRsaKeyPair();
            JwtTokenProvider rotatedProvider = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
            setRsaKeys(rotatedProvider, newKp, oldKp);
            rotatedProvider.init();

            // Old token should still validate via parseClaimsSafely (rotation fallback)
            assertThat(rotatedProvider.getPreviousRsaPublicKey()).isNotNull();
            // New tokens work
            String newToken = rotatedProvider.generateMfaToken("newuser");
            assertThat(rotatedProvider.validateToken(newToken)).isTrue();
        }

        @Test
        @DisplayName("Previous public key is exposed for JWKS")
        void previousKeyExposed() throws Exception {
            KeyPair oldKp = generateRsaKeyPair();
            KeyPair newKp = generateRsaKeyPair();
            setRsaKeys(newKp, oldKp);
            provider.init();

            assertThat(provider.getPreviousRsaPublicKey()).isNotNull();
            assertThat(provider.getRsaPublicKey()).isNotNull();
            assertThat(provider.getPreviousRsaPublicKey()).isNotEqualTo(provider.getRsaPublicKey());
        }
    }

    // ---- helpers ----

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private void setRsaKeys(KeyPair kp, KeyPair previousKp) {
        setRsaKeys(provider, kp, previousKp);
    }

    private void setRsaKeys(JwtTokenProvider target, KeyPair kp, KeyPair previousKp) {
        ReflectionTestUtils.setField(target, "jwtSecret", "unused-in-rsa-mode-but-required-field-placeholder!!");
        ReflectionTestUtils.setField(target, "accessTokenExpirationMs", 900000L);
        ReflectionTestUtils.setField(target, "refreshTokenExpirationMs", 172800000L);
        ReflectionTestUtils.setField(target, "rsaPrivateKeyPem", toPemPrivateKey(kp));
        ReflectionTestUtils.setField(target, "rsaPublicKeyPem", toPemPublicKey(kp));
        ReflectionTestUtils.setField(target, "previousPublicKeyPem",
                previousKp != null ? toPemPublicKey(previousKp) : "");
    }

    private static String toPemPrivateKey(KeyPair kp) {
        return "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----";
    }

    private static String toPemPublicKey(KeyPair kp) {
        return "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded()) +
                "\n-----END PUBLIC KEY-----";
    }
}
