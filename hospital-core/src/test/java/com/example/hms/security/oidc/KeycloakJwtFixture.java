package com.example.hms.security.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only helper that mints RSA-signed JWTs laid out like Keycloak realm tokens.
 *
 * <p>Used by {@code OidcResourceServerIntegrationTest} to exercise the resource-server
 * filter chain without needing a live Keycloak (or Testcontainers Keycloak) in CI. The
 * fixture generates a fresh 2048-bit RSA keypair per instance; wire the public key into
 * a {@link org.springframework.security.oauth2.jwt.NimbusJwtDecoder} to verify tokens
 * minted through {@link #mintToken(TokenSpec)}.</p>
 *
 * <p>Claim layout matches Keycloak's default mappers:
 * <ul>
 *   <li>{@code iss} — the issuer URL (configured per spec).</li>
 *   <li>{@code aud} — audience(s).</li>
 *   <li>{@code sub} — stable subject identifier.</li>
 *   <li>{@code preferred_username} — username the app echoes via {@code Authentication.getName()}.</li>
 *   <li>{@code realm_access.roles} — realm roles (mapped to {@code ROLE_*} authorities).</li>
 *   <li>{@code resource_access.&lt;client&gt;.roles} — per-client roles (also mapped).</li>
 * </ul></p>
 */
public final class KeycloakJwtFixture {

    private static final int RSA_KEY_SIZE_BITS = 2048;

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String keyId;

    public KeycloakJwtFixture() {
        KeyPairGenerator gen;
        try {
            gen = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyPairGenerator unavailable", e);
        }
        gen.initialize(RSA_KEY_SIZE_BITS);
        KeyPair kp = gen.generateKeyPair();
        this.publicKey = (RSAPublicKey) kp.getPublic();
        this.privateKey = (RSAPrivateKey) kp.getPrivate();
        this.keyId = "test-kid-" + UUID.randomUUID();
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String keyId() {
        return keyId;
    }

    /** Mint a signed JWT from the supplied spec. */
    public String mintToken(TokenSpec spec) {
        Instant now = Instant.now();
        Instant exp = now.plus(spec.ttl());

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer(spec.issuer())
                .subject(spec.subject())
                .audience(spec.audiences())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("preferred_username", spec.preferredUsername())
                .claim("email", spec.email())
                .claim("typ", "Bearer")
                .claim("azp", spec.authorizedParty())
                .claim("realm_access", Map.of("roles", spec.realmRoles()));

        if (!spec.clientRoles().isEmpty()) {
            Map<String, Object> resourceAccess = new HashMap<>();
            spec.clientRoles().forEach((client, roles) ->
                    resourceAccess.put(client, Map.of("roles", roles)));
            claims.claim("resource_access", resourceAccess);
        }

        if (spec.hospitalId() != null) {
            claims.claim("hospital_id", spec.hospitalId());
        }

        if (!spec.roleAssignments().isEmpty()) {
            claims.claim("role_assignments", spec.roleAssignments());
        }

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keyId)
                .build();

        SignedJWT jwt = new SignedJWT(header, claims.build());
        try {
            jwt.sign(new RSASSASigner(privateKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
        return jwt.serialize();
    }

    /**
     * Immutable spec for a single Keycloak-style JWT. Sensible defaults are applied
     * via {@link #defaults(String, String)} — override only what the test cares about.
     */
    public record TokenSpec(
            String issuer,
            String subject,
            String preferredUsername,
            String email,
            List<String> audiences,
            String authorizedParty,
            List<String> realmRoles,
            Map<String, List<String>> clientRoles,
            String hospitalId,
            List<String> roleAssignments,
            Duration ttl
    ) {
        public TokenSpec {
            if (audiences == null || audiences.isEmpty()) {
                throw new IllegalArgumentException("At least one audience is required");
            }
            if (realmRoles == null) {
                realmRoles = List.of();
            }
            if (clientRoles == null) {
                clientRoles = Map.of();
            }
            if (roleAssignments == null) {
                roleAssignments = List.of();
            }
            if (ttl == null) {
                ttl = Duration.ofMinutes(15);
            } else if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException(
                        "ttl must be positive when provided; pass null to accept the 15-minute default");
            }
        }

        public static TokenSpec defaults(String issuer, String audience) {
            return new TokenSpec(
                    issuer,
                    UUID.randomUUID().toString(),
                    "dev.doctor",
                    "dev.doctor@hms.local",
                    List.of(audience),
                    "hms-portal",
                    List.of("DOCTOR", "STAFF"),
                    Map.of(),
                    null,
                    List.of(),
                    Duration.ofMinutes(15));
        }

        public TokenSpec withRealmRoles(List<String> roles) {
            return new TokenSpec(issuer, subject, preferredUsername, email, audiences,
                    authorizedParty, roles, clientRoles, hospitalId, roleAssignments, ttl);
        }

        public TokenSpec withIssuer(String newIssuer) {
            return new TokenSpec(newIssuer, subject, preferredUsername, email, audiences,
                    authorizedParty, realmRoles, clientRoles, hospitalId, roleAssignments, ttl);
        }

        public TokenSpec withAudiences(List<String> newAudiences) {
            return new TokenSpec(issuer, subject, preferredUsername, email, newAudiences,
                    authorizedParty, realmRoles, clientRoles, hospitalId, roleAssignments, ttl);
        }

        public TokenSpec withHospitalId(String newHospitalId) {
            return new TokenSpec(issuer, subject, preferredUsername, email, audiences,
                    authorizedParty, realmRoles, clientRoles, newHospitalId, roleAssignments, ttl);
        }

        public TokenSpec withRoleAssignments(List<String> newRoleAssignments) {
            return new TokenSpec(issuer, subject, preferredUsername, email, audiences,
                    authorizedParty, realmRoles, clientRoles, hospitalId, newRoleAssignments, ttl);
        }

        public TokenSpec withTtl(Duration newTtl) {
            return new TokenSpec(issuer, subject, preferredUsername, email, audiences,
                    authorizedParty, realmRoles, clientRoles, hospitalId, roleAssignments, newTtl);
        }
    }
}
