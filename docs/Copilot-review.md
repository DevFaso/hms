hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java
Comment on lines 591 to 596
    public String getUsernameFromJWT(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
        Claims claims = jwtParser()
            .parseSignedClaims(token)
            .getPayload();
        return claims.getSubject();
    }
Copilot AI
7 minutes ago
In RS256 rotation scenarios, getUsernameFromJWT() also verifies only with the current key via jwtParser(). Any still-valid token signed with the previous key will fail parsing. Use the same rotation-aware parsing logic here (and in other helpers that parse claims) so old tokens remain usable during the configured grace period.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/impl/PharmacyServiceImpl.java
Comment on lines +33 to +35
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + dto.getHospitalId()));

Copilot AI
7 minutes ago
ResourceNotFoundException treats its constructor argument as an i18n message key (resolved via MessageUtil). Passing a literal string like "Hospital not found: " will render as a missing translation. Use a stable message key (and args) instead of a formatted English string.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/impl/PharmacyServiceImpl.java
Comment on lines +47 to +49
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found: " + id));
        return mapper.toResponseDTO(pharmacy);
Copilot AI
7 minutes ago
ResourceNotFoundException expects an i18n key, but this throws it with a literal message ("Pharmacy not found: ..."), which will resolve to a missing translation at runtime. Switch to a message key + args pattern consistent with ResourceNotFoundException usage.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
...re/src/main/java/com/example/hms/payload/dto/medication/MedicationCatalogItemRequestDTO.java
    @NotBlank(message = "Medication name (French) is required")
    @Size(max = 255)
    private String nameFr;

Copilot AI
7 minutes ago
MedicationCatalogItemRequestDTO does not require genericName, but the entity and migration define generic_name as NOT NULL. A null/blank genericName will pass controller validation and then fail at persistence time (500). Add validation to require genericName (e.g., @notblank) to match the DB/entity constraints.

Suggested change
    @NotBlank(message = "Generic name is required")
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-portal/src/app/mfa/mfa-enroll.html
Comment on lines +37 to +42
          <img
            [src]="'https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=' + otpauthUri"
            alt="TOTP QR Code"
            width="200"
            height="200"
          />
Copilot AI
7 minutes ago
The QR code image is generated via a third-party service (api.qrserver.com) with the full otpauth URI in the query string. This leaks the MFA secret to an external provider and through browser/network logs. Generate the QR code locally (client-side QR library) or have the backend return an image from a trusted endpoint instead.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/security/RateLimitFilter.java
Comment on lines +70 to +78
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        // Don't rate-limit health checks, actuator, or static assets
        return path.startsWith("/api/actuator")
                || path.startsWith("/assets/")
                || path.startsWith("/static/")
                || path.equals("/api/actuator/health");
    }
Copilot AI
7 minutes ago
PR description says rate limiting is for auth endpoints, but this filter currently rate-limits all API requests (everything except actuator/assets). If the intent is auth-only throttling, restrict the filter to the relevant paths (e.g., /api/auth/login, /api/auth/refresh, /api/auth/mfa/verify, etc.) in shouldNotFilter() or by registering it only for those endpoints.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/service/impl/MedicationCatalogItemServiceImpl.java
Comment on lines +33 to +35
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + dto.getHospitalId()));

Copilot AI
7 minutes ago
ResourceNotFoundException resolves its argument as a message key. Throwing it with a literal string ("Hospital not found: ...") will produce missing-translation output. Use a defined message key and pass the hospitalId as an argument instead.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/payload/dto/pharmacy/PharmacyRequestDTO.java
Comment on lines +53 to +59
    private PharmacyFulfillmentMode fulfillmentMode;

    @Builder.Default
    private int tier = 1;

    private UUID hospitalId;

Copilot AI
7 minutes ago
PharmacyRequestDTO does not enforce hospitalId as required, but PharmacyServiceImpl immediately dereferences dto.getHospitalId() and the DB schema requires hospital_id NOT NULL. This can result in a NullPointerException/500 instead of a 400 validation error. Add @NotNull (and consider @NotNull for fulfillmentMode to match the NOT NULL column).
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/model/UserMfaEnrollment.java
Comment on lines +56 to +61
    @Column(name = "totp_secret", length = 512)
    private String totpSecret;

    @Builder.Default
    @Column(name = "verified", nullable = false)
    private boolean verified = false;
Copilot AI
7 minutes ago
TOTP secrets are being stored directly on UserMfaEnrollment without any encryption/at-rest protection (plain String column). This is a sensitive credential equivalent; if the DB leaks, attackers can generate valid codes. Encrypt the secret before persisting (e.g., JPA AttributeConverter using an application-managed key) or store it in a dedicated secrets store.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/controller/MfaController.java
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(user.getUsername())
                    .eventType(AuditEventType.MFA_ENROLLED)
Copilot AI
7 minutes ago
verifyEnrollment() logs a success audit event with eventType MFA_ENROLLED, but the enum now includes MFA_VERIFIED and MFA_ENROLLED is already used for enrollment start. This makes audit trails ambiguous. Use MFA_VERIFIED for successful verification (and reserve MFA_ENROLLED for enrollment start).

Suggested change
                    .eventType(AuditEventType.MFA_ENROLLED)
                    .eventType(AuditEventType.MFA_VERIFIED)
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/resources/db/migration/changelog.xml
Comment on lines +543 to +561
    <!-- =================================================================
         V43: Password history for security hardening
         ================================================================= -->
    <changeSet id="V43-password-history" author="hms-team" runOnChange="false">
        <sqlFile path="V43__password_history.sql"
                 relativeToChangelogFile="true"
                 splitStatements="true"
                 stripComments="true"/>
    </changeSet>

    <!-- =================================================================
         V44: MFA TOTP secret and backup codes
         ================================================================= -->
    <changeSet id="V44-mfa-totp-backup-codes" author="hms-team" runOnChange="false">
        <sqlFile path="V44__mfa_totp_backup_codes.sql"
                 relativeToChangelogFile="true"
                 splitStatements="true"
                 stripComments="true"/>
    </changeSet>
Copilot AI
7 minutes ago
The Liquibase changelog only adds changeSets for V43 and V44, but this PR introduces additional migrations (V45+ for pharmacy). If those SQL files are not referenced here, they will never be applied in environments that rely on the changelog. Add changeSets for the new V45–V49 SQL files (or verify they’re included elsewhere) to ensure pharmacy schema migrations run.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-portal/src/app/mfa/mfa-enroll.html
Comment on lines +38 to +39
            [src]="'https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=' + otpauthUri"
            alt="TOTP QR Code"
Copilot AI
7 minutes ago
The otpauth URI is concatenated into a URL without encoding. If it contains characters like '&' or '#', the QR request can break (or be interpreted as additional query params). At minimum wrap the otpauthUri value with URL encoding before appending.
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/security/RateLimitFilter.java
Comment on lines +86 to +90
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + ip;
Copilot AI
7 minutes ago
The rate limit filter trusts the X-Forwarded-For header unconditionally. Any client can spoof this header to bypass throttling or to cause other clients to be throttled. Only honor X-Forwarded-For when running behind a trusted proxy (or use Spring's ForwardedHeaderFilter/server.forward-headers-strategy with a trusted proxy setup) and otherwise rely on getRemoteAddr().
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
docs/security-hardening-plan.md
| Password history (last 5) | ✅ | | | ✅ Done |
| MFA enforcement at login | | ✅ TOTP for privileged roles | SMS MFA | ✅ Done |
| HTTP rate limiting (Bucket4j) | | ✅ | | ✅ Done |
| Asymmetric JWT (RS256 + JWK) | | | ✅ When multi-service | 🔲 Future |
Copilot AI
7 minutes ago
This documentation is internally inconsistent: the MVP vs Full Vision table marks RS256/JWK as "Future" while later sections state Phase 6 is complete and list T-42/T-43/T-44 as done. Align the earlier summary tables with the implemented status to avoid confusion for operators and reviewers.

Suggested change
| Asymmetric JWT (RS256 + JWK) | | | ✅ When multi-service | 🔲 Future |
| Asymmetric JWT (RS256 + JWK) | | ✅ Implemented | | ✅ Done |
Copilot uses AI. Check for mistakes.
@TiegoOuedraogo	Reply...
hospital-core/src/main/java/com/example/hms/security/RateLimitFilter.java
Comment on lines +44 to +58
    private final int requestsPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
        log.info("[RATE-LIMIT] Configured at {} requests/minute per key", requestsPerMinute);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> createBucket());

Copilot AI
7 minutes ago
The in-memory buckets map grows unbounded (one entry per distinct IP/username) and has no eviction. Under bot traffic with many spoofed keys this can become a memory leak/DoS vector. Consider using a cache with TTL/size bounds (e.g., Caffeine) or periodic eviction similar to the token blacklist/ticket services.
