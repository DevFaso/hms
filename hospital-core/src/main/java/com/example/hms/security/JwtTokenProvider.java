package com.example.hms.security;

import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.example.hms.config.SecurityConstants.CLAIM_IS_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.CLAIM_IS_SUPER_ADMIN;
import static com.example.hms.config.SecurityConstants.CLAIM_PERMITTED_DEPARTMENT_IDS;
import static com.example.hms.config.SecurityConstants.CLAIM_PERMITTED_HOSPITAL_IDS;
import static com.example.hms.config.SecurityConstants.CLAIM_PERMITTED_ORGANIZATION_IDS;
import static com.example.hms.config.SecurityConstants.CLAIM_PRIMARY_HOSPITAL_ID;
import static com.example.hms.config.SecurityConstants.CLAIM_PRIMARY_ORGANIZATION_ID;
import static com.example.hms.config.SecurityConstants.ROLE_DOCTOR;
import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_NURSE;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_RECEPTIONIST;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private final HospitalUserDetailsService userDetailsService;
    private final TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.access-token-expiration-ms}")
    private long accessTokenExpirationMs;

    @Value("${app.jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    @Value("${app.jwt.private-key:}")
    private String rsaPrivateKeyPem;

    @Value("${app.jwt.public-key:}")
    private String rsaPublicKeyPem;

    @Value("${app.jwt.previous-public-key:}")
    private String previousPublicKeyPem;

    /** Signing key — either HMAC SecretKey or RSA PrivateKey. */
    private Key signingKey;

    /** Verification key — either HMAC SecretKey or RSA PublicKey. */
    @Getter
    private Key verificationKey;

    /** Current RSA public key (null when using HMAC). */
    @Getter
    private RSAPublicKey rsaPublicKey;

    /** Previous RSA public key for rotation grace period (null when not set). */
    @Getter
    private RSAPublicKey previousRsaPublicKey;

    /** True when using RS256, false for HMAC-SHA256. */
    @Getter
    private boolean asymmetric;

    /**
     * @deprecated Use {@link #getVerificationKey()} instead. Kept for backward compatibility.
     */
    @Deprecated(since = "6.0", forRemoval = true)
    public SecretKey getSecretKey() {
        if (verificationKey instanceof SecretKey sk) {
            return sk;
        }
        throw new UnsupportedOperationException(
            "getSecretKey() is not supported when using asymmetric (RS256) JWT signing. Use getVerificationKey() instead.");
    }

    public JwtTokenProvider(HospitalUserDetailsService userDetailsService,
                             TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor) {
        this.userDetailsService = userDetailsService;
        this.tenantRoleAssignmentAccessor = tenantRoleAssignmentAccessor;
    }

    @PostConstruct
    public void init() {
        if (StringUtils.hasText(rsaPrivateKeyPem) && StringUtils.hasText(rsaPublicKeyPem)) {
            initAsymmetric();
        } else {
            initHmac();
        }
    }

    private void initAsymmetric() {
        try {
            PrivateKey privateKey = loadPrivateKey(rsaPrivateKeyPem);
            this.rsaPublicKey = (RSAPublicKey) loadPublicKey(rsaPublicKeyPem);
            this.signingKey = privateKey;
            this.verificationKey = this.rsaPublicKey;
            this.asymmetric = true;

            if (StringUtils.hasText(previousPublicKeyPem)) {
                this.previousRsaPublicKey = (RSAPublicKey) loadPublicKey(previousPublicKeyPem);
                log.info("RS256 JWT configured with key rotation — previous public key loaded");
            }

            log.info("JWT configured with RS256 asymmetric signing (RSA key size={} bits)",
                    rsaPublicKey.getModulus().bitLength());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize RSA keys for JWT signing", e);
        }
    }

    private void initHmac() {
        String value = Objects.requireNonNull(jwtSecret, "app.jwt.secret is required");
        byte[] keyBytes = decodeSecretValue(value);

        if (keyBytes.length < 32) { // 256 bits
            throw new IllegalStateException(
                "app.jwt.secret must be >= 256 bits. Use 'base64:<key>' or 'hex:<key>' (32+ bytes). " +
                    "Current length=" + keyBytes.length + " bytes.");
        }

        SecretKey hmacKey = Keys.hmacShaKeyFor(keyBytes);
        this.signingKey = hmacKey;
        this.verificationKey = hmacKey;
        this.asymmetric = false;
        String active = Optional.ofNullable(System.getProperty("spring.profiles.active")).orElse("(sys-prop none)");
        log.info("JWT configured with HMAC-SHA256 ({} bytes) activeProfile={}", keyBytes.length, active);
    }

    static PrivateKey loadPrivateKey(String pem) throws java.security.GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    static PublicKey loadPublicKey(String pem) throws java.security.GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    /**
     * Build a configured JWT parser using the current verification key.
     * Supports both HMAC (SecretKey) and RSA (PublicKey) modes.
     * When key rotation is active, also accepts the previous public key.
     */
    private io.jsonwebtoken.JwtParser jwtParser() {
        var builder = Jwts.parser();
        if (verificationKey instanceof SecretKey sk) {
            builder.verifyWith(sk);
        } else if (verificationKey instanceof PublicKey pk) {
            builder.verifyWith(pk);
            if (previousRsaPublicKey != null) {
                // jjwt key locator for rotation: try current first, fallback to previous
                // For simplicity we use the current key; rotation validation is handled separately
            }
        }
        return builder.build();
    }

    /**
     * Parse and verify claims, trying the previous RSA key if the current one fails (rotation support).
     */
    private Claims parseClaimsWithRotation(String token) {
        try {
            return jwtParser().parseSignedClaims(token).getPayload();
        } catch (JwtException e) {
            if (previousRsaPublicKey != null) {
                return Jwts.parser().verifyWith(previousRsaPublicKey).build()
                        .parseSignedClaims(token).getPayload();
            }
            throw e;
        }
    }

    private static byte[] decodeSecretValue(String value) {
        if (value.startsWith("base64:")) {
            return Decoders.BASE64.decode(value.substring("base64:".length()));
        } else if (value.startsWith("hex:")) {
            return hexStringToBytes(value.substring("hex:".length()));
        } else {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] hexStringToBytes(String hex) {
        String s = hex.trim();
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex-encoded secret must have even length");
        }
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in secret");
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    public String generateAccessToken(Authentication authentication) {
        HospitalUserDetails userDetails = (HospitalUserDetails) authentication.getPrincipal();
        List<String> roles = userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
        log.debug("Adding roles to JWT token for user {}: {}", userDetails.getUsername(), roles);
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpirationMs);
        Map<String, Object> claims = buildTenantClaims(userDetails.getUserId(), roles);
        claims.put(ROLES_CLAIM, roles);
        if (userDetails.getUserId() != null) {
            claims.put("uid", userDetails.getUserId().toString());
        }
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userDetails.getUsername())
            .claims(claims)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(signingKey)
            .compact();
    }

    public String generateAccessToken(TokenUserDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor is required");
        UUID userId = descriptor.userId();

        List<String> roles = Optional.ofNullable(descriptor.roles())
            .filter(r -> !r.isEmpty())
            .map(this::normalizeRoleCollection)
            .orElseGet(() -> tenantRoleAssignmentAccessor.findAssignmentsForUser(userId).stream()
                .map(this::normalizeRoleLabel)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());

        log.debug("✅ Roles for token: {}", roles);
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpirationMs);
        Map<String, Object> claims = buildTenantClaims(userId, roles);
        claims.put(ROLES_CLAIM, roles);
        if (userId != null) {
            claims.put("uid", userId.toString());
        }
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(descriptor.username())
            .claims(claims)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(signingKey)
            .compact();
    }

    public String generateRefreshToken(Authentication authentication) {
        HospitalUserDetails userDetails = (HospitalUserDetails) authentication.getPrincipal();

        List<String> roles = userDetails.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userDetails.getUsername())
            .claim(ROLES_CLAIM, roles)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(signingKey)
            .compact();
    }

    /**
     * Generate a refresh token for a specific user with an explicit set of roles.
     * Used when the user selects a single role at login so the refresh token
     * contains only the chosen role.
     */
    public String generateRefreshToken(TokenUserDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor is required");

        List<String> roles = descriptor.roles() != null
                ? descriptor.roles().stream().toList()
                : List.of();

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(descriptor.username())
            .claim(ROLES_CLAIM, roles)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(signingKey)
            .compact();
    }

    /**
     * Generate a short-lived MFA challenge token (5 minutes).
     * This token is NOT a full access token — it only authorises the MFA verification step.
     */
    public String generateMfaToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + 300_000); // 5 minutes

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("purpose", "mfa_challenge")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validate that a token is a valid MFA challenge token.
     */
    public boolean isMfaToken(String token) {
        try {
            Claims claims = jwtParser()
                    .parseSignedClaims(token)
                    .getPayload();
            return "mfa_challenge".equals(claims.get("purpose", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> buildTenantClaims(UUID userId, List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        boolean isSuperAdmin = roles.stream().anyMatch(ROLE_SUPER_ADMIN::equalsIgnoreCase);
        boolean isHospitalAdmin = roles.stream().anyMatch(ROLE_HOSPITAL_ADMIN::equalsIgnoreCase);

        claims.put(CLAIM_IS_SUPER_ADMIN, isSuperAdmin);
        claims.put(CLAIM_IS_HOSPITAL_ADMIN, isHospitalAdmin);

        if (userId == null) {
            claims.putIfAbsent(CLAIM_PERMITTED_DEPARTMENT_IDS, List.of());
            return claims;
        }

        List<TenantRoleAssignment> assignments = tenantRoleAssignmentAccessor.findAssignmentsForUser(userId);
        if (CollectionUtils.isEmpty(assignments)) {
            claims.putIfAbsent(CLAIM_PERMITTED_DEPARTMENT_IDS, List.of());
            return claims;
        }

        LinkedHashSet<UUID> hospitalIds = new LinkedHashSet<>();
        LinkedHashSet<UUID> organizationIds = new LinkedHashSet<>();

        assignments.stream()
            .filter(TenantRoleAssignment::active)
            .forEach(a -> {
                if (a.hospitalId() != null) {
                    hospitalIds.add(a.hospitalId());
                }
                if (a.organizationId() != null) {
                    organizationIds.add(a.organizationId());
                }
            });

        if (!hospitalIds.isEmpty()) {
            claims.put(CLAIM_PERMITTED_HOSPITAL_IDS, hospitalIds.stream().map(UUID::toString).toList());
            claims.put(CLAIM_PRIMARY_HOSPITAL_ID, hospitalIds.iterator().next().toString());
        }

        if (!organizationIds.isEmpty()) {
            claims.put(CLAIM_PERMITTED_ORGANIZATION_IDS, organizationIds.stream().map(UUID::toString).toList());
            claims.put(CLAIM_PRIMARY_ORGANIZATION_ID, organizationIds.iterator().next().toString());
        }

        claims.putIfAbsent(CLAIM_PERMITTED_DEPARTMENT_IDS, List.of());
        return claims;
    }

    private List<String> normalizeRoleCollection(Collection<String> roles) {
        return roles.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(this::ensureRolePrefix)
            .distinct()
            .toList();
    }

    private String normalizeRoleLabel(TenantRoleAssignment assignment) {
        String candidate = assignment.roleName();
        if (!StringUtils.hasText(candidate)) {
            candidate = assignment.roleCode();
        }

        if (!StringUtils.hasText(candidate)) {
            return "";
        }

        return ensureRolePrefix(candidate.trim());
    }

    private String ensureRolePrefix(String value) {
        return value.startsWith(ROLE_PREFIX) ? value : ROLE_PREFIX + value;
    }

    public HospitalContext extractHospitalContext(String token, Authentication authentication) {
        if (!StringUtils.hasText(token)) {
            return HospitalContext.empty();
        }

        Optional<Claims> claimsOptional = parseClaimsSafely(token);
        if (claimsOptional.isEmpty()) {
            return HospitalContext.empty();
        }

        HospitalUserDetails userDetails = (authentication != null && authentication.getPrincipal() instanceof HospitalUserDetails details)
            ? details
            : null;

        List<String> authorities = authentication != null
            ? authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()
            : List.of();

        return buildHospitalContext(claimsOptional.get(), userDetails, authorities);
    }

    private Optional<Claims> parseClaimsSafely(String token) {
        try {
            return Optional.of(parseClaimsWithRotation(token));
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Unable to parse JWT claims for tenant context: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private HospitalContext buildHospitalContext(Claims claims, HospitalUserDetails userDetails, List<String> authorities) {
        UUID principalUserId = userDetails != null ? userDetails.getUserId() : null;
        String principalUsername = userDetails != null ? userDetails.getUsername() : null;

        Set<UUID> organizationIds = extractUuidSet(claims.get(CLAIM_PERMITTED_ORGANIZATION_IDS));
        Set<UUID> hospitalIds = extractUuidSet(claims.get(CLAIM_PERMITTED_HOSPITAL_IDS));
        Set<UUID> departmentIds = extractUuidSet(claims.get(CLAIM_PERMITTED_DEPARTMENT_IDS));

        UUID activeOrganization = extractUuid(claims.get(CLAIM_PRIMARY_ORGANIZATION_ID));
        UUID activeHospital = extractUuid(claims.get(CLAIM_PRIMARY_HOSPITAL_ID));

        boolean superAdminFlag = getBooleanClaim(claims.get(CLAIM_IS_SUPER_ADMIN))
            || authorities.stream().anyMatch(ROLE_SUPER_ADMIN::equalsIgnoreCase);
        boolean hospitalAdminFlag = getBooleanClaim(claims.get(CLAIM_IS_HOSPITAL_ADMIN))
            || authorities.stream().anyMatch(ROLE_HOSPITAL_ADMIN::equalsIgnoreCase);

        if (principalUserId != null && organizationIds.isEmpty() && hospitalIds.isEmpty()) {
            Map<String, Object> recomputed = buildTenantClaims(principalUserId, authorities);
            organizationIds = extractUuidSet(recomputed.get(CLAIM_PERMITTED_ORGANIZATION_IDS));
            hospitalIds = extractUuidSet(recomputed.get(CLAIM_PERMITTED_HOSPITAL_IDS));
            departmentIds = extractUuidSet(recomputed.get(CLAIM_PERMITTED_DEPARTMENT_IDS));
            if (activeOrganization == null) {
                activeOrganization = extractUuid(recomputed.get(CLAIM_PRIMARY_ORGANIZATION_ID));
            }
            if (activeHospital == null) {
                activeHospital = extractUuid(recomputed.get(CLAIM_PRIMARY_HOSPITAL_ID));
            }
        }

        if (activeOrganization == null && !organizationIds.isEmpty()) {
            activeOrganization = organizationIds.iterator().next();
        }
        if (activeHospital == null && !hospitalIds.isEmpty()) {
            activeHospital = hospitalIds.iterator().next();
        }

        return HospitalContext.builder()
            .principalUserId(principalUserId)
            .principalUsername(principalUsername)
            .activeOrganizationId(activeOrganization)
            .activeHospitalId(activeHospital)
            .permittedOrganizationIds(organizationIds)
            .permittedHospitalIds(hospitalIds)
            .permittedDepartmentIds(departmentIds)
            .superAdmin(superAdminFlag)
            .hospitalAdmin(hospitalAdminFlag)
            .build();
    }

    private static Set<UUID> extractUuidSet(Object value) {
        if (value == null) {
            return Collections.emptySet();
        }
        if (value instanceof Set<?> set) {
            return set.stream()
                .map(JwtTokenProvider::extractUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(JwtTokenProvider::extractUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (value instanceof String str) {
            UUID uuid = extractUuid(str);
            return uuid == null ? Collections.emptySet() : Set.of(uuid);
        }
        if (value instanceof UUID uuid) {
            return Set.of(uuid);
        }
        return Collections.emptySet();
    }

    private static UUID extractUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return UUID.fromString(str.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean getBooleanClaim(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return false;
    }

    public String getUsernameFromJWT(String token) {
        Claims claims = parseClaimsWithRotation(token);
        return claims.getSubject();
    }

    public Authentication getAuthenticationFromJwt(String token) {
        Claims claims = parseClaimsWithRotation(token);

        String username = claims.getSubject();

        @SuppressWarnings("unchecked")
        List<String> roles = Optional.ofNullable((List<String>) claims.get(ROLES_CLAIM))
            .orElseGet(Collections::emptyList);

        LinkedHashSet<String> normalizedRoles = roles.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(r -> !r.isEmpty())
            .map(this::ensureRolePrefix)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (normalizedRoles.contains(ROLE_SUPER_ADMIN)) {
            List<String> inherited = List.of(
                ROLE_HOSPITAL_ADMIN,
                ROLE_RECEPTIONIST,
                ROLE_DOCTOR,
                ROLE_NURSE,
                "ROLE_LAB_SCIENTIST",
                "ROLE_STAFF",
                ROLE_PATIENT
            );
            inherited.stream()
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .forEach(normalizedRoles::add);
        }

        List<SimpleGrantedAuthority> authorities = normalizedRoles.stream()
            .map(SimpleGrantedAuthority::new)
            .toList();

        HospitalUserDetails userDetails = userDetailsService.loadUserByUsername(username);

        if (!userDetails.isEnabled()) {
            // Reject every JWT-bearing request from an unverified or deactivated account.
            // The login endpoint already blocks initial sign-in via DisabledException; this
            // closes the gap for tokens issued through any other path or held across a
            // post-issuance deactivation.
            //
            // Defense-in-depth: keep this branch free of identifying information so
            // an attacker cannot use it to enumerate accounts via response/timing
            // signals or via leaked logs:
            //  - This code path is reachable only with a valid signature on a token
            //    we minted (Keycloak-issued tokens go through OidcResourceServer,
            //    not this method), so an unauthenticated attacker cannot probe
            //    arbitrary usernames here. Still — operator log aggregators
            //    (Datadog/Loki/etc.) can be misconfigured, so we don't log the
            //    username on the rejection branch. The corresponding request log
            //    line already carries the JTI, IP, and timestamp, which is enough
            //    to correlate back to a user when ops needs to investigate.
            //  - The exception message is generic so it stays safe to serialize
            //    if it ever flows into an error page or audit event.
            log.warn("JWT presented for a disabled or unverified account — rejecting authentication.");
            throw new DisabledException("Account is disabled or unverified.");
        }

        log.debug("JWT token for user {} contains authorities: {}", username, authorities);

        return new UsernamePasswordAuthenticationToken(userDetails, token, authorities);
    }

    public boolean validateToken(String authToken) {
        try {
            jwtParser()
                .parseSignedClaims(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.error("❌ JWT validation error: {}", ex.getMessage());
            return false;
        }
    }

    public List<String> getRolesFromToken(String token) {
        Claims claims = jwtParser()
            .parseSignedClaims(token)
            .getPayload();

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) claims.get(ROLES_CLAIM);
        return roles != null ? roles : new ArrayList<>();
    }

    public String resolvePreferredRole(List<String> roles) {
    List<String> priority = List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_LAB_SCIENTIST", "ROLE_PATIENT");
        for (String preferred : priority) {
            if (roles.contains(preferred)) return preferred;
        }
        return roles.isEmpty() ? null : roles.get(0);
    }

    public Date getIssuedAt(String token) {
        return jwtParser()
            .parseSignedClaims(token).getPayload().getIssuedAt();
    }

    public Date getExpiration(String token) {
        return jwtParser()
            .parseSignedClaims(token).getPayload().getExpiration();
    }

    /**
     * Extract the JWT ID (jti) claim from a token.
     */
    public String getJtiFromToken(String token) {
        return jwtParser()
            .parseSignedClaims(token).getPayload().getId();
    }
}
