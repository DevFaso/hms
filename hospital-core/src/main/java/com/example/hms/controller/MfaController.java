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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MFA enrollment and verification endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private static final String AUTHENTICATED_USER_NOT_FOUND = "Authenticated user not found";

    private final MfaService mfaService;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RefreshTokenCookieService refreshTokenCookieService;

    /**
     * Start TOTP enrollment — returns secret, otpauth URI, and backup codes.
     */
    @PostMapping("/enroll")
    public ResponseEntity<Object> enroll(@AuthenticationPrincipal UserDetails principal) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException(AUTHENTICATED_USER_NOT_FOUND));

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

        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException(AUTHENTICATED_USER_NOT_FOUND));

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
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException(AUTHENTICATED_USER_NOT_FOUND));

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
        List<String> roles = assignmentRepository.findByUser_IdAndActiveTrue(user.getId()).stream()
                .map(a -> a.getRole().getName())
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

        return ResponseEntity.ok(body);
    }

    // DTO for MFA login verification (unauthenticated)
    public record MfaLoginVerifyRequest(
            @jakarta.validation.constraints.NotBlank String mfaToken,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 6, max = 8) String code
    ) {}
}
