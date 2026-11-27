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
import com.example.hms.security.GlobalSessionRevocationService;
import com.example.hms.security.IdleSessionGate;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
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
    private static final String NOT_FULLY_AUTHENTICATED = "mfa.enrollment.partialSession";

    /** QA D911 (re-test 3): every answer here is shown as it is, so it is a bundle key. */
    private static final String INVALID_MFA_TOKEN = "mfa.token.invalid";
    private static final String PICK_NOT_HONOURED = "auth.mfa.pickNotHonoured";

    private final MfaService mfaService;
    /**
     * QA r64: the user's own removal of their authenticator. A provider, like the gates below: a
     * {@code @WebMvcTest} slice of this controller need not build the service.
     */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.service.MfaResetService> mfaResetService;
    private final org.springframework.context.MessageSource messageSource;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final RefreshTokenCookieService refreshTokenCookieService;
    private final IdleSessionGate idleSessionGate;
    /** The challenge token is single-use: a spent or force-logged-out one is refused at verify. */
    private final TokenBlacklistService tokenBlacklistService;
    private final GlobalSessionRevocationService globalSessionRevocationService;
    private final com.example.hms.security.LoginAttemptService loginAttemptService;
    /** The source the login offered roles from, so a picked role is re-checked against the same list. */
    private final com.example.hms.security.HospitalUserDetailsService userDetailsService;
    /** Providers, like {@code AuthController}'s gate: a {@code @WebMvcTest} slice has neither bean. */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.security.DowntimeStateService> downtimeState;
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.security.provider.ProviderMfaGate> providerMfaGate;
    /** The session lifetimes, for the sign-in answer's sessionEndsAt; a provider so a slice need not build it. */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.controller.support.AuthControllerProperties> authProps;
    /** Ends a super admin's tracked impersonation on a new sign-in; a provider so a slice need not build it. */
    private final org.springframework.beans.factory.ObjectProvider<com.example.hms.service.SupportImpersonationService> supportImpersonation;

    /**
     * Why {@link #honouredPick} refused, told apart: a challenge that no longer
     * parses (expired meanwhile) is the usual invalid-token 401; a challenge
     * carrying no role is a 403 (no session without an active role); a pick no
     * longer held is said so. The last two are audited.
     */
    private ResponseEntity<Object> pickRefusal(String mfaToken, User user) {
        Optional<List<String>> picked = jwtTokenProvider.mfaTokenSelectedRoles(mfaToken);
        if (picked.isEmpty()) {
            return ResponseEntity.status(401).body(new MessageResponse(message(INVALID_MFA_TOKEN)));
        }
        if (picked.get().isEmpty()) {
            // A challenge carrying no role: an account with no active role
            // opens no session, with or without the code.
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(user.getUsername())
                    .eventType(AuditEventType.MFA_FAILURE)
                    .status(AuditStatus.FAILURE)
                    .eventDescription("MFA sign-in refused: no active role")
                    .build());
            return ResponseEntity.status(403).body(new MessageResponse(
                    com.example.hms.utility.MessageUtil.resolve(AuthController.NO_ACTIVE_ROLE_KEY)));
        }
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(user.getUsername())
                .eventType(AuditEventType.MFA_FAILURE)
                .status(AuditStatus.FAILURE)
                .eventDescription("MFA sign-in refused: the role picked at login is no longer held")
                .build());
        return ResponseEntity.status(401)
                .body(new MessageResponse(com.example.hms.utility.MessageUtil.resolve(PICK_NOT_HONOURED)));
    }

    /**
     * The session's role set: the roles the challenge carries from the login
     * that the user still holds ({@link com.example.hms.security.SessionRoles#stillHeld},
     * never a role the login did not decide on). Empty when the challenge
     * carries none or none is still held; {@link Optional#empty()} when the
     * challenge no longer parses. Held roles are read from the same source the
     * login offered them from (assignments and legacy global roles alike),
     * widened as the login widened them, so a pick that worked without a
     * challenge also works with one.
     */
    private Optional<com.example.hms.security.SessionRoles.Granted> honouredPick(String mfaToken, String username) {
        Optional<List<String>> picked = jwtTokenProvider.mfaTokenSelectedRoles(mfaToken);
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        List<String> carried = picked.get();
        if (carried.isEmpty()) {
            // Nothing carried: nothing to look up, and nothing to mint.
            return Optional.of(com.example.hms.security.SessionRoles.stillHeld(carried, List.of()));
        }
        return Optional.of(com.example.hms.security.SessionRoles.stillHeld(carried,
                userDetailsService.loadUserByUsername(username).getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .filter(java.util.Objects::nonNull)
                        .toList()));
    }

    /** 401 for a caller holding only a partial mfaToken, not a full access token. */
    private ResponseEntity<Object> notFullyAuthenticated() {
        return ResponseEntity.status(401).body(new MessageResponse(message(NOT_FULLY_AUTHENTICATED)));
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
     * User decision 2026-11-06: in read-only mode an enrolment goes through only when it is what
     * stands between the user and reading: the login's challenge session (a required role with no
     * authenticator yet), or a provider user the second-factor gate refuses everything else to. Any
     * other enrolment is an optional change, refused like the filter refuses a write. The filter
     * cannot tell them apart: it runs before authentication.
     */
    private Optional<ResponseEntity<Object>> readOnlyRefusal(User user, Authentication authentication) {
        com.example.hms.security.DowntimeStateService downtime = downtimeState.getIfAvailable();
        com.example.hms.security.DowntimeStateService.DowntimeSnapshot snapshot =
            downtime == null ? com.example.hms.security.DowntimeStateService.DowntimeSnapshot.NORMAL : downtime.snapshot();
        if (!snapshot.readOnly()
                || com.example.hms.security.MfaChallengeScope.isChallengeSession(authentication)
                || blockedByTheProviderGate(user, authentication)) {
            return Optional.empty();
        }
        boolean custom = snapshot.message() != null;
        return Optional.of(ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Readonly-Mode", "true")
            .header("Retry-After", "300")
            .<Object>body(Map.of("error", "READ_ONLY_MODE",
                "message", custom ? snapshot.message()
                    : message(com.example.hms.security.ReadOnlyModeFilter.DEFAULT_MESSAGE_KEY),
                "customMessage", custom)));
    }

    private boolean blockedByTheProviderGate(User user, Authentication authentication) {
        com.example.hms.security.provider.ProviderMfaGate gate = providerMfaGate.getIfAvailable();
        return gate != null && gate.isProviderUser(user.getId(), user.getUsername())
            && !gate.secondFactorPresented(authentication);
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
        Optional<ResponseEntity<Object>> readOnly = readOnlyRefusal(user, authentication);
        if (readOnly.isPresent()) return readOnly.get();

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
        Optional<ResponseEntity<Object>> readOnly = readOnlyRefusal(user, authentication);
        if (readOnly.isPresent()) return readOnly.get();

        boolean verified = mfaService.verifyEnrollment(user.getId(), request.code(), authentication);

        if (verified) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(user.getId())
                    .userName(user.getUsername())
                    .eventType(AuditEventType.MFA_VERIFIED)
                    .status(AuditStatus.SUCCESS)
                    .eventDescription("TOTP enrollment verified")
                    .build());
            // QA D911: in the reader's language.
            return ResponseEntity.ok(new MessageResponse(
                message("mfa.enrollment.verified")));
        }

        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .userId(user.getId())
                .userName(user.getUsername())
                .eventType(AuditEventType.MFA_FAILURE)
                .status(AuditStatus.FAILURE)
                .eventDescription("Invalid TOTP code during enrollment verification")
                .build());
        return ResponseEntity.badRequest().body(new MessageResponse(message("mfa.enrollment.invalidCode")));
    }

    /**
     * QA r64: the user removes their own authenticator, after a current code or an unused backup code from it in
     * {@code X-Mfa-Token} (403 with a reason code otherwise). The enrolment and the backup codes are deleted and the
     * removal audited ({@code MFA_RESET}); a role that requires two-step verification sets one up again at the next
     * sign-in.
     */
    @org.springframework.web.bind.annotation.DeleteMapping
    public ResponseEntity<Object> removeOwn(@AuthenticationPrincipal UserDetails principal,
                                            Authentication authentication,
                                            @RequestHeader(value = "X-Mfa-Token", required = false) String stepUpCode) {
        Optional<User> authenticated = resolveFullyAuthenticatedUser(principal);
        if (authenticated.isEmpty()) return notFullyAuthenticated();
        User user = authenticated.get();
        Optional<ResponseEntity<Object>> readOnly = readOnlyRefusal(user, authentication);
        if (readOnly.isPresent()) return readOnly.get();
        mfaResetService.getObject().removeOwn(user, authentication, stepUpCode);
        return ResponseEntity.ok(new MessageResponse(message("mfa.remove.done")));
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
                    .body(new MessageResponse(message(INVALID_MFA_TOKEN)));
        }

        String username = jwtTokenProvider.getUsernameFromJWT(request.mfaToken());
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        // The role picked at login, checked now, before any code is reserved,
        // checked or spent: a refusal costs the user nothing, and a challenge
        // whose pick cannot be honoured is refused, never widened.
        Optional<com.example.hms.security.SessionRoles.Granted> honoured = honouredPick(request.mfaToken(), username);
        if (honoured.isEmpty() || honoured.get().isEmpty()) {
            return pickRefusal(request.mfaToken(), user);
        }
        com.example.hms.security.SessionRoles.Granted granted = honoured.get();

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
                    .body(new MessageResponse(message("mfa.login.tooManyCodes")));
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
                    .body(new MessageResponse(message("mfa.login.invalidCode")));
        }

        // The code is right. Claim the challenge atomically BEFORE anything is
        // spent or issued: of two concurrent verifies of one challenge, only
        // the one that claims it records the TOTP use, spends the backup code
        // and gets a session; the other spends nothing.
        if (!claim(request.mfaToken())) {
            return ResponseEntity.status(401)
                    .body(new MessageResponse(message(INVALID_MFA_TOKEN)));
        }
        boolean spent = totp
                ? mfaService.claimTotpStep(user.getId(), totpStep.getAsLong())
                : mfaService.spendBackupCode(backupCode.get());
        if (!spent) {
            // Used by another request in between: this one signs nobody in,
            // and its challenge is already spent, so it must start again. The
            // attempt still counts (never a way to reset the lockout).
            return ResponseEntity.status(401)
                    .body(new MessageResponse(message("mfa.login.codeJustUsed")));
        }
        mfaService.clearCodeAttempts(user.getId());

        // Issue the full JWT pair with the roles the login decided on (still
        // held, checked above): the role picked, or the single role held, as
        // the sign-in without a challenge mints it. Held spellings
        // (honouredPick) are canonical ROLE_* authorities, as the idle gate's
        // machine-role carve-out needs.
        List<String> roles = granted.roles();
        var descriptor = granted.descriptor(user.getId(), username);
        // A verified TOTP or backup code: both tokens carry amr [pwd, otp], the
        // second-factor proof the provider MFA gate reads (AC-13), and the
        // refresh token passes it on to the access tokens it later mints.
        Instant verifiedAt = Instant.now();
        // QA D2184: the pair opens a sign-in session that logout revokes whole.
        String sessionId = JwtTokenProvider.newSessionId();
        String accessToken = jwtTokenProvider.generateAccessToken(descriptor, verifiedAt, sessionId);
        String refreshToken = jwtTokenProvider.generateRefreshToken(descriptor, verifiedAt, sessionId);

        userCredentialLifecycleService.recordSuccessfulLogin(user.getId());
        // A super admin signing in again leaves any impersonation of the
        // previous session behind: end it (audited) so the new session works.
        supportImpersonation.ifAvailable(
                service -> service.endForImpersonator(user.getId(), "the super admin signed in again"));
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

        auditEventLogService.logEvent(com.example.hms.security.audit.SignInAuditRows.signedIn(user, true));

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
                .sessionEndsAt(sessionEndsAt(refreshToken, roles))
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
        // the held role codes (canonical ROLE_* authorities, see above).
        idleSessionGate.touchIfHuman(user.getId(),
                AuthorityUtils.createAuthorityList(roles.toArray(new String[0])));

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

    /** A bundle message in the reader's language (the request's Accept-Language). */
    private String message(String key) {
        return messageSource.getMessage(key, null, org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    /** When the session this sign-in opened ends (epoch ms); null when the lifetimes are not configured here. */
    private Long sessionEndsAt(String refreshToken, List<String> roles) {
        var props = authProps.getIfAvailable();
        if (props == null) {
            return null;
        }
        Instant signedIn = java.util.Optional.ofNullable(jwtTokenProvider.signedInAt(refreshToken)).orElseGet(Instant::now);
        return props.sessionLifetime().endsAt(signedIn, roles).toEpochMilli();
    }
}
