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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.example.hms.config.SecurityConstants.*;

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

    @Getter
    private SecretKey secretKey;

    public JwtTokenProvider(HospitalUserDetailsService userDetailsService,
                             TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor) {
        this.userDetailsService = userDetailsService;
        this.tenantRoleAssignmentAccessor = tenantRoleAssignmentAccessor;
    }

    @PostConstruct
    public void init() {
        String value = Objects.requireNonNull(jwtSecret, "app.jwt.secret is required");
        byte[] keyBytes = decodeSecretValue(value);

        if (keyBytes.length < 32) { // 256 bits
            throw new IllegalStateException(
                "app.jwt.secret must be >= 256 bits. Use 'base64:<key>' or 'hex:<key>' (32+ bytes). " +
                    "Current length=" + keyBytes.length + " bytes.");
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        String active = Optional.ofNullable(System.getProperty("spring.profiles.active")).orElse("(sys-prop none)");
        log.info("JWT secret configured ({} bytes) activeProfile={} rawPrefix={}...", keyBytes.length, active,
            value.length() > 12 ? value.substring(0, 12) : value);
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
        return Jwts.builder()
            .setSubject(userDetails.getUsername())
            .addClaims(claims)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(secretKey)
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
        return Jwts.builder()
            .setSubject(descriptor.username())
            .addClaims(claims)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(secretKey)
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
            .setSubject(userDetails.getUsername())
            .claim(ROLES_CLAIM, roles)
            .setIssuedAt(now)
            .setExpiration(expiryDate)
            .signWith(secretKey)
            .compact();
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
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
            return Optional.of(claims);
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
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(secretKey)
            .build()
            .parseClaimsJws(token)
            .getBody();
        return claims.getSubject();
    }

    public Authentication getAuthenticationFromJwt(String token) {
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(secretKey)
            .build()
            .parseClaimsJws(token)
            .getBody();

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

        log.debug("JWT token for user {} contains authorities: {}", username, authorities);

        return new UsernamePasswordAuthenticationToken(userDetails, token, authorities);
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.error("❌ JWT validation error: {}", ex.getMessage());
            return false;
        }
    }

    public List<String> getRolesFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
            .setSigningKey(secretKey)
            .build()
            .parseClaimsJws(token)
            .getBody();

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
        return Jwts.parserBuilder().setSigningKey(secretKey).build()
            .parseClaimsJws(token).getBody().getIssuedAt();
    }

    public Date getExpiration(String token) {
        return Jwts.parserBuilder().setSigningKey(secretKey).build()
            .parseClaimsJws(token).getBody().getExpiration();
    }
}
