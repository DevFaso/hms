package com.example.hms.controller;

import com.example.hms.security.audit.WriteAudited;
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
import com.example.hms.security.GlobalSessionRevocationService;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.UserCredentialLifecycleService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * MFA enrollment and verification endpoints.
 */
@Slf4j
@RestController
@WriteAudited(skip = true, reason = "service emits MFA_ENROLLED / MFA_VERIFIED / MFA_FAILURE")
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private static final String AUTHENTICATED_USER_NOT_FOUND = "Authenticated user not found";
    private static final String NOT_FULLY_AUTHENTICATED =
            "MFA enrollment requires a fully authenticated session — partial mfaToken is not sufficient.";

    private static final String INVALID_MFA_TOKEN = "Invalid or expired MFA token.";
    private static final String PICK_NOT_HONOURED = "auth.mfa.pickNotHonoured";

    private final MfaService mfaService;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final IdleSessionGate idleSessionGate;
    /** The challenge token is single-use: a spent or force-logged-out one is refused at verify. */
    private final TokenBlacklistService tokenBlacklistService;
    private final GlobalSessionRevocationService globalSessionRevocationService;
    private final com.example.hms.security.LoginAttemptService loginAttemptService;
    /** The source the login offered roles from, so a picked role is re-checked against the same list. */
    private final com.example.hms.security.HospitalUserDetailsService userDetailsService;

    /**
     * The roles the challenge picked at login that the user still holds: an
     * empty list when none was picked, and {@link Optional#empty()} when the
     * pick cannot be honoured (the token no longer parses, or no picked role
     * is still held). Held roles are read from the same source the login
     * offered them from (assignments and legacy global roles alike), so a
     * pick that worked without a challenge also works with one.
     */
    private Optional<List<String>> honouredPick(String mfaToken, String username) {
        Optional<List<String>> picked = jwtTokenProvider.mfaTokenSelectedRoles(mfaToken);
        if (picked.isEmpty() || picked.get().isEmpty()) {
            return picked;
        }
        List<String> held = userDetailsService.loadUserByUsername(username).getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .filter(java.util.Objects::nonNull)
                .toList();
        // The held spelling, not the one typed at login: the idle gate's
        // machine-role carve-out and the response compare exact strings.
        List<String> stillHeld = picked.get().stream()
                .flatMap(role -> held.stream().filter(role::equalsIgnoreCase).limit(1))
                .distinct()
                .toList();
        return stillHeld.isEmpty() ? Optional.empty() : Optional.of(stillHeld);
    }

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
     * With an active authenticator this starts its replacement, which needs a
     * full session and the step-up in {@code X-Mfa-Token} (a current code or
     * an unused backup code; 403 with a reason code otherwise), and stays
     * pending until verify-enrollment confirms it: see {@link MfaService#enrollTotp}.
     */
    @PostMapping("/enroll")
    public ResponseEntity<Object> enroll(@AuthenticationPrincipal UserDetails principal,
                                         Authentication authentication,
                                         @RequestHeader(value = "X-Mfa-Token", required = false) String stepUpCode) {
        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();

        MfaService.MfaEnrollmentResult result = mfaService.enrollTotp(user, authentication, stepUpCode);

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(user.getUsername())
                .eventType(AuditEventType.MFA_ENROLLED)
                .status(AuditStatus.PENDING)
                .eventDescription(result.replacing()
                        ? "TOTP replacement started (pending until confirmed)"
                        : "TOTP enrollment started")
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
            Authentication authentication,
            @Valid @RequestBody MfaVerifyRequest request) {

        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();

        boolean verified = mfaService.verifyEnrollment(user.getId(), request.code(), authentication);

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
        if (!jwtTokenProvider.isMfaToken(request.mfaToken()) || isSpentOrRevoked(request.mfaToken())) {
            return ResponseEntity.status(401)
                    .body(new MessageResponse(INVALID_MFA_TOKEN));
        }

        String username = jwtTokenProvider.getUsernameFromJWT(request.mfaToken());
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // The role picked at login, checked now, before any code is reserved,
        // checked or spent: a refusal costs the user nothing, and a challenge
        // whose pick cannot be honoured is refused, never widened.
        Optional<List<String>> honoured = honouredPick(request.mfaToken(), username);
        if (honoured.isEmpty()) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(username)
                    .eventType(AuditEventType.MFA_FAILURE)
                    .status(AuditStatus.FAILURE)
                    .eventDescription("MFA sign-in refused: the role picked at login is no longer held")
                    .build());
            return ResponseEntity.status(401)
                    .body(new MessageResponse(com.example.hms.utility.MessageUtil.resolve(PICK_NOT_HONOURED)));
        }
        List<String> pickedRoles = honoured.get();

        // The per-user budget of wrong LOGIN codes (its own, never the
        // step-ups': see MfaService), reserved BEFORE the code is checked, so
        // neither rotating IPs nor parallel requests buy more guesses.
        if (!mfaService.reserveCodeAttempt(user.getId())) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(username)
                    .eventType(AuditEventType.MFA_FAILURE)
                    .status(AuditStatus.FAILURE)
                    .eventDescription("MFA code refused during login: too many wrong codes")
                    .build());
            return ResponseEntity.status(429)
                    .body(new MessageResponse("Too many wrong codes. Try again in a few minutes."));
        }

        // Check the code without writing anything yet: TOTP first, then a
        // backup code. A typo leaves the challenge usable.
        OptionalLong totpStep = mfaService.currentTotpStep(user.getId(), request.code());
        boolean totp = totpStep.isPresent();
        Optional<java.util.UUID> backupCode = totp
                ? Optional.empty()
                : mfaService.matchingBackupCode(user.getId(), request.code());
        boolean usedBackup = backupCode.isPresent();

        if (!totp && !usedBackup) {
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

        // The code is right. Claim the challenge atomically BEFORE anything is
        // spent or issued: of two concurrent verifies of one challenge, only
        // the one that claims it records the TOTP use, spends the backup code
        // and gets a session; the other spends nothing.
        if (!claim(request.mfaToken())) {
            return ResponseEntity.status(401)
                    .body(new MessageResponse(INVALID_MFA_TOKEN));
        }
        boolean spent = totp
                ? mfaService.claimTotpStep(user.getId(), totpStep.getAsLong())
                : mfaService.spendBackupCode(backupCode.get());
        if (!spent) {
            // Used by another request in between: this one signs nobody in,
            // and its challenge is already spent, so it must start again. The
            // attempt still counts (never a way to reset the lockout).
            return ResponseEntity.status(401)
                    .body(new MessageResponse("That code was just used. Sign in again."));
        }
        mfaService.clearCodeAttempts(user.getId());

        // Issue the full JWT pair. A role picked at login (still held, checked
        // above) is the session's only role, as the sign-in without a
        // challenge mints it: the challenge never widens it.
        var activeAssignments = pickedRoles.isEmpty()
                ? assignmentRepository.findByUser_IdAndActiveTrue(user.getId())
                : List.<com.example.hms.model.UserRoleHospitalAssignment>of();
        List<String> roles = !pickedRoles.isEmpty() ? pickedRoles : activeAssignments.stream()
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
        List<String> roleCodes = !pickedRoles.isEmpty() ? pickedRoles : activeAssignments.stream()
                .map(a -> a.getRole().getCode())
                .distinct()
                .toList();

        var descriptor = new TokenUserDescriptor(user.getId(), username, roles);
        // A verified TOTP or backup code: both tokens carry amr [pwd, otp], the
        // second-factor proof the provider MFA gate reads (AC-13), and the
        // refresh token passes it on to the access tokens it later mints.
        Instant verifiedAt = Instant.now();
        String accessToken = jwtTokenProvider.generateAccessToken(descriptor, verifiedAt);
        String refreshToken = jwtTokenProvider.generateRefreshToken(descriptor, verifiedAt);

        userCredentialLifecycleService.recordSuccessfulLogin(user.getId());
        // The sign-in is complete only now: clear the password-failure count
        // here, as the password-only sign-in does, or a challenged user stays
        // one typo from the lockout.
        loginAttemptService.resetAttempts(user.getId());

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

    /**
     * The challenge token was already used to complete a sign-in, or it
     * predates the last force-logout-all: refused like an expired one.
     */
    private boolean isSpentOrRevoked(String challengeToken) {
        try {
            String jti = jwtTokenProvider.getJtiFromToken(challengeToken);
            return (jti != null && tokenBlacklistService.isBlacklisted(jti))
                    // Fresh: this mints a session, like refresh.
                    || globalSessionRevocationService.revokesFresh(jwtTokenProvider.getIssuedAt(challengeToken));
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException expired) {
            // Expired since isMfaToken read it: "sign in again", not a 500.
            return true;
        }
    }

    /** The one caller that spends the challenge (TokenBlacklistService#claim). */
    private boolean claim(String challengeToken) {
        try {
            String jti = jwtTokenProvider.getJtiFromToken(challengeToken);
            return jti != null
                    && tokenBlacklistService.claim(jti, jwtTokenProvider.getExpiration(challengeToken).getTime());
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException expired) {
            // The challenge expired while the code was being checked (a backup
            // code's BCrypt scan takes time): "sign in again", not a 500.
            return false;
        }
    }

    // DTO for MFA login verification (unauthenticated)
    public record MfaLoginVerifyRequest(
            @jakarta.validation.constraints.NotBlank String mfaToken,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 6, max = 8) String code
    ) {}
}
