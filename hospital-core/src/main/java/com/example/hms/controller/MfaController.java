package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.JwtResponse;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.mfa.MfaEnrollmentResponse;
import com.example.hms.payload.dto.mfa.MfaVerifyRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.UserCredentialLifecycleService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MFA enrollment and verification endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private static final String AUTHENTICATED_USER_NOT_FOUND = "Authenticated user not found";
    private static final String NOT_FULLY_AUTHENTICATED =
            "MFA enrollment requires a fully authenticated session — partial mfaToken is not sufficient.";

    private final MfaService mfaService;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final IdleSessionGate idleSessionGate;

    /** 401 for a caller holding only a partial mfaToken, not a full access token. */
    private ResponseEntity<Object> notFullyAuthenticated() {
        return ResponseEntity.status(401).body(new MessageResponse(NOT_FULLY_AUTHENTICATED));
    }

    /**
     * Resolve the authenticated user, or empty when the caller holds only a
     * partial mfaToken instead of a full access token.
     *
     * <p>This used to be {@code requireFullAuth}, which returned an error
     * response on failure and NULL on success — so every caller then
     * dereferenced {@code principal} on a line where nothing local proved it
     * non-null. It was safe, but only via a helper whose return value inverted
     * the meaning, which neither a reader nor a static analyser can follow.
     * Resolving the user inside the guard puts the null check and the
     * dereference in the same place.
     */
    private Optional<User> resolveFullyAuthenticatedUser(UserDetails principal) {
        if (principal == null) {
            return Optional.empty();
        }
        return Optional.of(userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException(AUTHENTICATED_USER_NOT_FOUND)));
    }

    /**
     * Start TOTP enrollment — returns secret, otpauth URI, and backup codes.
     */
    @PostMapping("/enroll")
    public ResponseEntity<Object> enroll(@AuthenticationPrincipal UserDetails principal) {
        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(user);

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(user.getUsername())
                .eventType(AuditEventType.MFA_ENROLLED)
                .status(AuditStatus.PENDING)
                .eventDescription("TOTP enrollment started")
                .build());

        return ResponseEntity.ok(new MfaEnrollmentResponse(
                result.secret(),
                result.otpauthUri(),
                result.qrCodeDataUrl(),
                result.backupCodes()
        ));
    }

    /**
     * Verify enrollment — user submits first TOTP code to confirm authenticator works.
     */
    @PostMapping("/verify-enrollment")
    public ResponseEntity<Object> verifyEnrollment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody MfaVerifyRequest request) {

        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();

        boolean verified = mfaService.verifyEnrollment(user.getId(), request.code());

        if (verified) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(user.getUsername())
                    .eventType(AuditEventType.MFA_VERIFIED)
                    .status(AuditStatus.SUCCESS)
                    .eventDescription("TOTP enrollment verified")
                    .build());
            return ResponseEntity.ok(new MessageResponse("MFA enrollment verified successfully."));
        }

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(user.getUsername())
                .eventType(AuditEventType.MFA_FAILURE)
                .status(AuditStatus.FAILURE)
                .eventDescription("Invalid TOTP code during enrollment verification")
                .build());
        return ResponseEntity.badRequest().body(new MessageResponse("Invalid TOTP code."));
    }

    /**
     * Check current user's MFA status.
     */
    @GetMapping("/status")
    public ResponseEntity<Object> status(@AuthenticationPrincipal UserDetails principal) {
        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();

        boolean enabled = mfaService.isMfaEnabled(user.getId());
        return ResponseEntity.ok(Map.of("mfaEnabled", enabled));
    }

    /**
     * Verify MFA code during login flow.
     * Accepts the short-lived mfaToken (from login response) + TOTP code or backup code.
     * Returns full JWT pair on success.
     */
    @PostMapping("/verify")
    public ResponseEntity<Object> verifyMfa(@Valid @RequestBody MfaLoginVerifyRequest request,
                                            HttpServletResponse httpResponse) {
        // Validate the mfaToken
        if (!jwtTokenProvider.isMfaToken(request.mfaToken())) {
            return ResponseEntity.status(401)
                    .body(new MessageResponse("Invalid or expired MFA token."));
        }

        String username = jwtTokenProvider.getUsernameFromJWT(request.mfaToken());
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // Try TOTP code first, then backup code
        boolean verified = mfaService.verifyCode(user.getId(), request.code());
        boolean usedBackup = false;

        if (!verified) {
            verified = mfaService.verifyBackupCode(user.getId(), request.code());
            usedBackup = verified;
        }

        if (!verified) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(username)
                    .eventType(AuditEventType.MFA_FAILURE)
                    .status(AuditStatus.FAILURE)
                    .eventDescription("Invalid MFA code during login")
                    .build());
            return ResponseEntity.status(401)
                    .body(new MessageResponse("Invalid MFA code."));
        }

        // MFA passed — issue full JWT pair
        var activeAssignments = assignmentRepository.findByUser_IdAndActiveTrue(user.getId());
        List<String> roles = activeAssignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();
        // Authority strings for the idle-gate touch must be Spring Security
        // canonical form (ROLE_*). `Role.name` is not guaranteed to carry the
        // ROLE_ prefix (per the entity Javadoc, examples include both
        // "HOSPITAL_ADMIN" and "ROLE_SUPER_ADMIN"), so naively wrapping
        // `roles` would break IdleSessionGate#hasMachineRole's exact-string
        // carve-out — a real machine client (e.g. ROLE_FHIR_CLIENT) routed
        // through this path would be misclassified as human and pinned in
        // Redis. Use `Role.code`, which is uppercased on persist (Role.java)
        // and mirrors the AuthController#refreshToken pattern (line ~617).
        List<String> roleCodes = activeAssignments.stream()
                .map(a -> a.getRole().getCode())
                .distinct()
                .toList();

        var descriptor = new TokenUserDescriptor(user.getId(), username, roles);
        String accessToken = jwtTokenProvider.generateAccessToken(descriptor);
        String refreshToken = jwtTokenProvider.generateRefreshToken(descriptor);

        userCredentialLifecycleService.recordSuccessfulLogin(user.getId());

        AuditEventType eventType = usedBackup ? AuditEventType.MFA_BACKUP_USED : AuditEventType.MFA_VERIFIED;
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(username)
                .eventType(eventType)
                .status(AuditStatus.SUCCESS)
                .eventDescription(usedBackup ? "MFA verified via backup code" : "MFA verified via TOTP")
                .build());

        log.info("[MFA] Login MFA verified for user='{}' usedBackup={}", username, usedBackup);

        String preferredRole = jwtTokenProvider.resolvePreferredRole(roles);
        var body = JwtResponse.builder()
                .tokenType("Bearer")
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roles(roles)
                .roleName(preferredRole)
                .active(user.isActive())
                .profilePictureUrl(user.getProfileImageUrl())
                .forcePasswordChange(user.isForcePasswordChange())
                .forceUsernameChange(user.isForceUsernameChange())
                .build();

        // S-01: deliver refresh token via HttpOnly cookie
        try {
            long expMs = jwtTokenProvider.getExpiration(refreshToken).getTime();
            refreshTokenCookieService.write(httpResponse, refreshToken,
                    Math.max(0L, expMs - System.currentTimeMillis()));
        } catch (Exception ex) {
            log.warn("[MFA] Failed to set refresh cookie: {}", ex.getMessage());
        }

        // Seed the idle window on token issue, mirroring
        // AuthController.authenticateUser. The authority list is built from
        // role codes — see the roleCodes block above for the rationale.
        idleSessionGate.touchIfHuman(user.getId(),
                AuthorityUtils.createAuthorityList(roleCodes.toArray(new String[0])));

        return ResponseEntity.ok(body);
    }

    // DTO for MFA login verification (unauthenticated)
    public record MfaLoginVerifyRequest(
            @jakarta.validation.constraints.NotBlank String mfaToken,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 6, max = 8) String code
    ) {}
}
