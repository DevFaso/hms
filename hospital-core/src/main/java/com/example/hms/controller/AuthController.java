package com.example.hms.controller;

import static com.example.hms.config.SecurityConstants.ROLE_PREFIX;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.BootstrapSignupRequest;
import com.example.hms.payload.dto.SessionBootstrapResponseDTO;
import com.example.hms.payload.dto.EmailVerificationRequestDTO;
import com.example.hms.payload.dto.EmailVerificationResponseDTO;
import com.example.hms.payload.dto.JwtResponse;
import com.example.hms.payload.dto.LoginRequest;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.PasswordResetConfirmDTO;
import com.example.hms.payload.dto.credential.UserCredentialHealthDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentDTO;
import com.example.hms.payload.dto.credential.UserMfaEnrollmentRequestDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactDTO;
import com.example.hms.payload.dto.credential.UserRecoveryContactRequestDTO;
import com.example.hms.payload.dto.credential.VerifyRecoveryContactRequest;
import com.example.hms.controller.support.AuthControllerProperties;
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.security.provider.ProviderMfaGate;
import com.example.hms.service.MfaService;
import com.example.hms.security.WsTicketService;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.AuthBootstrapService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    private static final String EMAIL_VERIFICATION_FAILED = "Email verification failed.";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String NOT_AUTHENTICATED = "Not authenticated.";
    private static final String USER_NOT_FOUND = "User not found.";

    /**
     * Shown when authentication succeeded but the account is not enabled.
     * Names both activation routes (the patient's emailed link, the staff
     * member's role-confirmation code) and the fallback, without disclosing
     * which one this account is on — see the DisabledException arm of
     * {@link #login} for why that matters.
     */

    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuthBootstrapService authBootstrapService;

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthNotificationFacade authNotification;

    private final UserService userService;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final TokenBlacklistService tokenBlacklistService;
    /** Force-logout-all: a refresh token issued before it mints nothing (the filter's own rule). */
    private final com.example.hms.security.GlobalSessionRevocationService globalSessionRevocationService;
    private final com.example.hms.security.IdleSessionGate idleSessionGate;
    private final com.example.hms.security.ImpersonationSessionTracker impersonationSessionTracker;
    private final LoginAttemptService loginAttemptService;
    private final AuditEventLogService auditEventLogService;
    private final PasswordHistoryService passwordHistoryService;
    private final MfaService mfaService;
    private final WsTicketService wsTicketService;
    private final RefreshTokenCookieService refreshTokenCookieService;
    /**
     * Bundles the four {@code @Value}-injected configuration scalars
     * the controller previously took as separate constructor params:
     * frontend base URL, MFA-required roles, OIDC-required flag, and
     * OIDC issuer URI. Aggregation drops the constructor parameter
     * count from 21 to 18. Sonar S107 ("constructor has too many
     * parameters") still fires at 18 — the residual is documented as
     * "won't fix" in docs/SonarQubeInstructions.md §Pattern 7
     * because full helper-class extraction is a feature-scope refactor
     * (changes the auth-surface architecture) rather than a
     * Sonar-cleanup chore.
     *
     * <p>Field-level docstrings for the original scalars (KC-5 cutover
     * gate, RFC-8414 Link header behaviour) moved to the accessor
     * comments on {@link AuthControllerProperties}.</p>
     */
    private final AuthControllerProperties authProps;

    /**
     * QA D418: the anonymous re-send of an activation code may reach a given
     * assignment at most once per cooldown, so the endpoint cannot be used to
     * flood a number with SMS (or run up their cost). Enforced on the
     * assignment row ({@code claimActivationResend}), so it holds across
     * instances; the per-IP limit of {@code RateLimitFilter} also applies.
     */
    static final java.time.Duration RESEND_COOLDOWN = java.time.Duration.ofMinutes(5);
    /**
     * The provider MFA gate (provider plan AC-13). Through an
     * {@link ObjectProvider}: a {@code @WebMvcTest} slice has no such bean,
     * and its absence leaves login, refresh and the bootstrap as they were.
     */
    private final ObjectProvider<ProviderMfaGate> providerMfaGate;

    public AuthController(UserRepository userRepository,
            UserRoleHospitalAssignmentRepository assignmentRepository,
            AuthBootstrapService authBootstrapService,
            UserService userService,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            AuthNotificationFacade authNotification,
            UserCredentialLifecycleService userCredentialLifecycleService,
            TokenBlacklistService tokenBlacklistService,
            com.example.hms.security.GlobalSessionRevocationService globalSessionRevocationService,
            com.example.hms.security.IdleSessionGate idleSessionGate,
            com.example.hms.security.ImpersonationSessionTracker impersonationSessionTracker,
            LoginAttemptService loginAttemptService,
            AuditEventLogService auditEventLogService,
            PasswordHistoryService passwordHistoryService,
            MfaService mfaService,
            WsTicketService wsTicketService,
            RefreshTokenCookieService refreshTokenCookieService,
            AuthControllerProperties authProps,
            ObjectProvider<ProviderMfaGate> providerMfaGate) {
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.authBootstrapService = authBootstrapService;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authNotification = authNotification;
        this.userCredentialLifecycleService = userCredentialLifecycleService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.globalSessionRevocationService = globalSessionRevocationService;
        this.idleSessionGate = idleSessionGate;
        this.impersonationSessionTracker = impersonationSessionTracker;
        this.loginAttemptService = loginAttemptService;
        this.auditEventLogService = auditEventLogService;
        this.passwordHistoryService = passwordHistoryService;
        this.mfaService = mfaService;
        this.wsTicketService = wsTicketService;
        this.refreshTokenCookieService = refreshTokenCookieService;
        this.authProps = authProps;
        this.providerMfaGate = providerMfaGate;

        // KC-5 cutover signal: surface the gate state in the startup log so the
        // ops on-call running the cutover runbook can confirm the flip took
        // effect without grepping request-time WARN lines. Paths are reported
        // with the /api context-path (server.servlet.context-path=/api), which
        // is what hits the load balancer — the message must match the URL ops
        // are actually curling in their smoke checks.
        if (authProps.oidcRequired()) {
            log.info("[OIDC] app.auth.oidc.required=true — legacy POST /api/auth/login + POST /api/auth/token/refresh will return 410 Gone");
        } else {
            log.info("[OIDC] app.auth.oidc.required=false — legacy POST /api/auth/login + POST /api/auth/token/refresh accept username/password");
        }
    }

    /**
     * Deprecated: Public patient self-registration removed (web app is staff-only).
     * Always returns 410 Gone. Mobile apps should implement controlled onboarding.
     */
    @PostMapping("/register")
    public ResponseEntity<Object> registerUser() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new MessageResponse("Public self-registration is no longer available."));
    }

    @GetMapping("/bootstrap-status")
    public ResponseEntity<Object> bootstrapStatus() {
        long count = userRepository.count();
        if (count > 0) {
            log.info("[BOOTSTRAP] Status check while disallowed (userCount={})", count);
        }
        return ResponseEntity.ok(java.util.Map.of(
                "allowed", count == 0,
                "userCount", count));
    }

    @PostMapping("/bootstrap-signup")
    public ResponseEntity<Object> bootstrapSignup(@Valid @RequestBody BootstrapSignupRequest request) {
        long count = userRepository.count();
        if (count > 0) {
            log.info("[BOOTSTRAP] Signup attempted but users already exist (count={})", count);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Bootstrap not allowed; users already exist."));
        }
        var result = userService.bootstrapFirstUser(request);
        if (!result.isSuccess()) {
            log.info("[BOOTSTRAP] Signup failed: {}", result.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
        }
        log.info("[BOOTSTRAP] Super Admin created: {}", result.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/login")
    public ResponseEntity<Object> authenticateUser(
            @Parameter(description = "Login request payload", required = true) @Valid @RequestBody LoginRequest loginRequest,
            HttpServletResponse httpResponse) {
        // KC-5 prep: short-circuit the legacy issuer when the soak flag is on.
        // Callers must migrate to the Keycloak Auth Code + PKCE flow.
        if (authProps.oidcRequired()) {
            log.warn("🔐 [LOGIN] Rejected — legacy issuer disabled (app.auth.oidc.required=true). user='{}'",
                    loginRequest.getUsername());
            return legacyIssuerGone(
                    "Legacy username/password login is disabled. Sign in via Single Sign-On.");
        }
        long start = System.nanoTime();
        log.info("🔐 [LOGIN] Attempting login for user='{}' at {}", loginRequest.getUsername(),
                java.time.Instant.now());

        // ── Lockout check (T-12) ──
        // One lookup, the same for a known and an unknown name: see
        // LoginAttemptService on why the 423 must not tell them apart.
        long remainMs = loginAttemptService.remainingLockMs(loginRequest.getUsername());
        if (remainMs > 0) {
            long remainMin = Math.max(1, (remainMs + 59_999) / 60_000);
            log.warn("🔐 [LOGIN] Account '{}' is locked", loginRequest.getUsername());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.ACCOUNT_LOCKED)
                    .eventDescription("Login rejected — account locked")
                    .userName(loginRequest.getUsername())
                    .status(AuditStatus.FAILURE)
                    .build());
            return ResponseEntity.status(423)
                    .body(new MessageResponse(MessageUtil.resolve("auth.login.locked", remainMin)));
        }

        try {
            log.debug("🔐 [LOGIN] Authenticating via AuthenticationManager...");
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));
            log.debug("🔐 [LOGIN] AuthenticationManager succeeded; building tokens...");
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Collect ALL roles the user holds across assignments.
            //
            // An authority is not necessarily a role. Spring Security 7's
            // DaoAuthenticationProvider adds a FactorGrantedAuthority —
            // FACTOR_PASSWORD — to record WHICH authentication factor was
            // satisfied, for its multi-factor support. It arrived with the
            // Spring Boot 4.1 upgrade and it is not something a user can hold,
            // select, or be authorised by.
            //
            // Unfiltered it broke three things at once: every single-role user
            // suddenly had two authorities and so was forced through the
            // role picker; the picker offered "FACTOR PASSWORD" as a card; and
            // selecting it passed the holds-this-role check below and minted a
            // token whose only role no guard admits.
            var allRoles = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
                    .distinct()
                    .toList();

            // ── Multi-role selection gate ──
            // If the user holds more than one role and has NOT yet chosen, ask them to pick.
            if (allRoles.size() > 1 && (loginRequest.getSelectedRole() == null || loginRequest.getSelectedRole().isBlank())) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("🔐 [LOGIN] Multi-role selection required for user='{}' roles={} in {}ms",
                        loginRequest.getUsername(), allRoles, elapsedMs);

                // QA D536: the picker answer carries the account's real
                // credential flags; it used to default them to false, so a
                // multi-role user with a temporary password was not told.
                var pickerUser = userRepository.findByUsernameIgnoreCase(loginRequest.getUsername()).orElse(null);
                var body = JwtResponse.builder()
                        .roleSelectionRequired(true)
                        .availableRoles(allRoles)
                        .username(loginRequest.getUsername())
                        .forcePasswordChange(pickerUser != null && pickerUser.isForcePasswordChange())
                        .forceUsernameChange(pickerUser != null && pickerUser.isForceUsernameChange())
                        .build();
                return ResponseEntity.ok(body);
            }

            // Determine the effective role list for token generation
            List<String> effectiveRoles;
            if (loginRequest.getSelectedRole() != null && !loginRequest.getSelectedRole().isBlank()) {
                String typed = loginRequest.getSelectedRole().trim();
                // Validate the user actually holds this role, and use the held
                // spelling: the MFA required-roles check and the token compare
                // exact strings, so "Role_Doctor" must not skip the challenge.
                String selected = allRoles.stream().filter(r -> r.equalsIgnoreCase(typed)).findFirst().orElse(null);
                if (selected == null) {
                    log.warn("🔐 [LOGIN] User '{}' tried to select role '{}' but holds only {}",
                            loginRequest.getUsername(), typed, allRoles);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(new MessageResponse(MessageUtil.resolve("auth.login.roleNotHeld", typed)));
                }
                effectiveRoles = List.of(selected);
            } else {
                // Single-role user — proceed as before
                effectiveRoles = allRoles;
            }

            // Generate tokens scoped to the effective roles
            var user = userRepository.findByUsername(loginRequest.getUsername())
                    .orElseThrow(() -> new RuntimeException("Authenticated user not found"));

            var descriptor = new com.example.hms.security.TokenUserDescriptor(
                    user.getId(), user.getUsername(), effectiveRoles);

            // ── MFA challenge gate (T-28) ──
            // A provider user (a live assignment at a pharmacy or laboratory)
            // is challenged whatever the role list says (AC-13): the token the
            // challenge mints is the only one the provider MFA gate accepts.
            if (mfaService != null
                    && (isMfaRequiredForUser(effectiveRoles) || isProviderUserChallenged(user.getId(), user.getUsername()))) {
                boolean mfaEnabled = mfaService.isMfaEnabled(user.getId());
                // The roles this sign-in would mint (the one picked, or the
                // single one held) ride the challenge to the session it opens,
                // so the code changes nothing about which roles it carries.
                String mfaToken = jwtTokenProvider.generateMfaToken(user.getUsername(), effectiveRoles);

                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                        .eventType(AuditEventType.MFA_CHALLENGE)
                        .eventDescription("MFA challenge issued")
                        .userName(user.getUsername())
                        .userId(user.getId())
                        .status(AuditStatus.PENDING)
                        .build());

                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("🔐 [LOGIN] MFA required for user='{}', enrolled={} in {}ms",
                        user.getUsername(), mfaEnabled, elapsedMs);

                var body = JwtResponse.builder()
                        .mfaRequired(true)
                        .mfaEnrolled(mfaEnabled)
                        .mfaToken(mfaToken)
                        .username(user.getUsername())
                        .build();
                return ResponseEntity.ok(body);
            }

            String accessToken = jwtTokenProvider.generateAccessToken(descriptor);
            String refreshToken = jwtTokenProvider.generateRefreshToken(descriptor);
            log.debug("🔐 [LOGIN] Tokens generated (effectiveRoles={}); fetching profiles...", effectiveRoles);

            userCredentialLifecycleService.recordSuccessfulLogin(user.getId());
            loginAttemptService.resetAttempts(user.getId());

            // Pull details from related profiles
            var patient = user.getPatientProfile();
            var staff = user.getStaffProfile();

            String profileType;
            if (staff != null) {
                profileType = "STAFF";
            } else if (patient != null) {
                profileType = "PATIENT";
            } else {
                profileType = null;
            }

            String preferredRole = jwtTokenProvider.resolvePreferredRole(effectiveRoles);

            // ── Hospital assignment context (source of truth: UserRoleHospitalAssignment) ──
            var hospitalContext = resolveHospitalContext(user.getId());

            var body = JwtResponse.builder()
                    .tokenType("Bearer")
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .phoneNumber(user.getPhoneNumber())
                    .dateOfBirth(patient != null ? patient.getDateOfBirth() : null)
                    .gender(patient != null ? patient.getGender() : null)
                    .roles(effectiveRoles)
                    .profileType(profileType)
                    .licenseNumber(staff != null ? staff.getLicenseNumber() : null)
                    .patientId(patient != null ? patient.getId() : null)
                    .staffId(staff != null ? staff.getId() : null)
                    .roleName(preferredRole)
                    .active(user.isActive())
                    .profilePictureUrl(user.getProfileImageUrl())
                    .forcePasswordChange(user.isForcePasswordChange())
                    .forceUsernameChange(user.isForceUsernameChange())
                    .primaryHospitalId(hospitalContext.primaryHospitalId())
                    .primaryHospitalName(hospitalContext.primaryHospitalName())
                    .hospitalIds(hospitalContext.hospitalIds())
                    .build();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("🔐 [LOGIN] Success user='{}' effectiveRoles={} in {}ms", loginRequest.getUsername(), effectiveRoles, elapsedMs);
            // S-01: deliver refresh token via HttpOnly cookie (in addition to JSON body during rollout)
            writeRefreshCookie(httpResponse, refreshToken);
            // ── Idle session timeout — seed the idle window on token issue ──
            // Without this, the very first authenticated request after login
            // hits the idle gate with no tracker entry and is treated as
            // "idle past the window" (both InMemoryIdleSessionTracker.isIdle
            // and RedisIdleSessionTracker.isIdle return true on a missing
            // key). Touching here means the session becomes idle only after
            // the configured window of true inactivity, not immediately on
            // first use.
            idleSessionGate.touchIfHuman(user.getId(),
                AuthorityUtils.createAuthorityList(effectiveRoles.toArray(new String[0])));
            return ResponseEntity.ok(body);

        } catch (BadCredentialsException ex) {
            loginAttemptService.recordFailure(loginRequest.getUsername());
            auditLoginFailure(loginRequest.getUsername(), "Login failed: bad credentials");
            log.warn("🔐 [LOGIN] Bad credentials for user='{}'", loginRequest.getUsername());
            // D64: the refusal is shown on the sign-in screen, so it speaks the
            // caller's language rather than always English.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.login.badCredentials")));
        } catch (DisabledException ex) {
            // "Please verify your email" named a flow most accounts do not
            // have — staff activate by confirming a role assignment with an
            // emailed code — and that dead end is what gets reported as "I
            // never received an activation email". The wording below covers
            // both routes without asserting either, and is identical for every
            // inactive account. QA D168: the provider now checks the account
            // status only AFTER the password matched
            // (SecurityConfig#checkPasswordBeforeAccountStatus), so a wrong
            // password gets the bad-credentials answer above and this arm is
            // reached only by someone who knows the password. It IS counted toward the lockout, so the probe is
            // throttled like any other failed login. That is only safe because
            // every activation path clears the counter —
            // UserRoleHospitalAssignmentServiceImpl#activateVerifiedAssignment
            // (behind verifyAssignmentByCode, which the holder drives, and
            // confirmAssignment, which their registrar drives), #verifyEmail
            // below, which is the endpoint that actually serves verification
            // (UserServiceImpl#verifyEmail has no caller), and
            // UserServiceImpl#updateUser / #restoreUser when an administrator
            // switches an account back on. The welcome mail hands out temp
            // credentials, so trying them before confirming the code is the
            // expected mistake, and without those resets the holder would be
            // locked out at the exact moment activation succeeds.
            loginAttemptService.recordFailure(loginRequest.getUsername());
            auditLoginFailure(loginRequest.getUsername(), "Login failed: account not active");
            log.warn("🔐 [LOGIN] Disabled account user='{}'", loginRequest.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.login.inactive")));
        } catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("🔐 [LOGIN] Unexpected failure user='{}' after {}ms : {} - {}", loginRequest.getUsername(),
                    elapsedMs, ex.getClass().getSimpleName(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.login.failed")));
        } finally {
            resetSecurityContextToAnonymous();
        }
    }

    /**
     * A refused sign-in, in the audit trail (D20). The account id when the
     * typed name resolves to exactly one account, never the typed name itself
     * (it may be a password typed into the wrong field) and never the password.
     * An unknown name is recorded with no id at all.
     */
    private void auditLoginFailure(String typedName, String description) {
        UUID accountId = null;
        if (typedName != null && !typedName.isBlank()) {
            List<UUID> ids = userRepository.findIdsByUsernameIgnoreCase(typedName);
            accountId = ids.size() == 1 ? ids.get(0) : null;
        }
        auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.LOGIN_FAILURE)
                .eventDescription(description)
                .userId(accountId)
                .resourceId(accountId == null ? null : accountId.toString())
                .entityType("USER")
                .status(AuditStatus.FAILURE)
                .build());
    }

    @Operation(summary = "Verify email address", description = "Endpoint to verify email using a token sent after registration. "
            +
            "Activates user account if token is valid.")
    @ApiResponse(responseCode = "200", description = "Email verified successfully", content = @Content(schema = @Schema(implementation = EmailVerificationResponseDTO.class)))
    @ApiResponse(responseCode = "400", description = "Invalid or expired token", content = @Content(schema = @Schema(implementation = EmailVerificationResponseDTO.class)))

    @PostMapping("/verify-email")
    public ResponseEntity<Object> verifyEmail(@Valid @RequestBody EmailVerificationRequestDTO dto) {
        var userOpt = userRepository.findByEmail(dto.getEmail());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new EmailVerificationResponseDTO(EMAIL_VERIFICATION_FAILED, false));
        }
        var user = userOpt.get();
        if (!java.util.Objects.equals(dto.getToken(), user.getActivationToken())
                || user.getActivationTokenExpiresAt() == null
                || user.getActivationTokenExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new EmailVerificationResponseDTO(EMAIL_VERIFICATION_FAILED, false));
        }

        // An account an administrator switched off (V184) is not verified
        // from below: no activation, no lockout reset, no assignment change.
        if (user.getDeactivatedByAdminAt() != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new EmailVerificationResponseDTO(EMAIL_VERIFICATION_FAILED, false));
        }

        // 1. Activate the user account
        user.setActive(true);
        user.setActivationToken(null);
        user.setActivationTokenExpiresAt(null);
        userRepository.save(user);
        // Refusals collected while the account was inactive must not outlive
        // the activation: the disabled arm of /auth/login counts toward the
        // lockout, so five attempts before verifying would otherwise leave the
        // holder locked out at the moment the link finally works. This is the
        // endpoint that actually runs — UserService#verifyEmail has no caller.
        loginAttemptService.resetAttempts(user.getId());

        // 2. Activate all patient role assignments for this user
        var assignments = assignmentRepository.findByUserId(user.getId());
        int activated = 0;
        for (var assignment : assignments) {
            if ("ROLE_PATIENT".equalsIgnoreCase(assignment.getRole().getCode())
                    && !Boolean.TRUE.equals(assignment.getActive())) {
                assignment.setActive(true);
                assignmentRepository.save(assignment);
                activated++;
            }
        }
        log.info("✅ Email verified for user '{}'. Activated {} patient assignment(s).",
                user.getUsername(), activated);

        return ResponseEntity.ok(new EmailVerificationResponseDTO("Email verified successfully.", true));
    }

    @Operation(summary = "Verify email via link",
               description = "GET version for email verification links. Activates user and patient role assignments.")
    @GetMapping("/verify-email")
    public ResponseEntity<Object> verifyEmailViaLink(
            @RequestParam("email") @Email @NotBlank String email,
            @RequestParam("token") @NotBlank String token) {
        var dto = new EmailVerificationRequestDTO();
        dto.setEmail(email);
        dto.setToken(token);
        return verifyEmail(dto);
    }

    @Operation(summary = "Resend verification email",
               description = "Generates a new activation token and resends the verification email.")
    @PostMapping("/resend-verification")
    public ResponseEntity<Object> resendVerificationEmail(
            @RequestParam(value = "email", required = false) @Email String emailParam,
            @RequestParam(value = "phone", required = false) String phoneParam,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        // QA D542: the portal now sends the contact in the body - a phone
        // number or address in the query string lands in access logs and the
        // browser history. The query parameters stay for older clients.
        String email = firstNonBlank(body == null ? null : body.get("email"), emailParam);
        String phone = firstNonBlank(body == null ? null : body.get("phone"), phoneParam);
        if (email == null || email.isBlank()) {
            if (phone == null || phone.isBlank()) {
                throw new com.example.hms.exception.BusinessException("auth.r7.contactRequired");
            }
            return resendActivationByPhone(phone.trim());
        }
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Don't reveal whether email exists
            return ResponseEntity.ok(new MessageResponse(
                    "If the email is registered, a new verification link has been sent."));
        }
        var user = userOpt.get();
        if (user.isActive()) {
            return ResponseEntity.ok(new MessageResponse(
                    "Account is already verified. You can log in."));
        }

        // Generate new token
        user.setActivationToken(java.util.UUID.randomUUID().toString());
        user.setActivationTokenExpiresAt(LocalDateTime.now().plusDays(1));
        userRepository.save(user);

        String activationLink = com.example.hms.utility.ActivationLinks.build(
                authProps.frontendBaseUrl(), user.getEmail(), user.getActivationToken());
        try {
            authNotification.email().sendActivationEmail(user.getEmail(), activationLink);
            log.info("📧 Resent verification email to '{}'", com.example.hms.utility.ActivationDeliveryTracker.maskEmail(user.getEmail()));
        } catch (Exception e) {
            log.warn("⚠️ Failed to resend verification email to '{}': {}",
                    com.example.hms.utility.ActivationDeliveryTracker.maskEmail(user.getEmail()), e.getClass().getSimpleName());
        }
        resendPendingAssignmentCodes(user);

        return ResponseEntity.ok(new MessageResponse(
                "If the email is registered, a new verification link has been sent."));
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred.trim() : fallback;
    }

    /**
     * QA D311/D418: a phone-only account (a patient registered at the desk
     * without an e-mail) had no way to ask for a new activation. Re-sends the
     * code of every assignment still waiting for confirmation — by SMS, and by
     * e-mail as well when the account has one. Same neutral answer whether or
     * not the number is known, so the endpoint does not reveal accounts.
     */
    private ResponseEntity<Object> resendActivationByPhone(String phone) {
        MessageResponse neutral = new MessageResponse(
                "If the number is registered, a new activation code has been sent.");
        // Same lookup as the reset-by-phone flow: the number as typed at the
        // desk ("70 00 00 00") is stored in E.164.
        String e164 = com.example.hms.utility.PhoneNumbers.toE164(
                phone, com.example.hms.utility.PhoneNumbers.DEFAULT_CALLING_CODE);
        java.util.Optional<com.example.hms.model.User> found = e164 == null
                ? java.util.Optional.empty() : userRepository.findByPhoneNumber(e164);
        found.or(() -> userRepository.findByPhoneNumber(phone))
                .filter(user -> !user.isActive())
                .ifPresent(this::resendPendingAssignmentCodes);
        return ResponseEntity.ok(neutral);
    }

    /**
     * Re-sends the code of every assignment of {@code user} still waiting for
     * confirmation, through the assignment notifier (e-mail when the account
     * has an address, SMS when it has a number). Staff stay inactive until
     * that code is typed, so the plain verification link alone did not
     * activate a staff account (QA D311/D418).
     */
    private void resendPendingAssignmentCodes(com.example.hms.model.User user) {
        var assignments = authNotification.assignments();
        if (assignments == null) {
            return;
        }
        for (var assignment : assignmentRepository.findByUserId(user.getId())) {
            boolean pending = !Boolean.TRUE.equals(assignment.getActive())
                    && assignment.getConfirmationCode() != null
                    && !assignment.getConfirmationCode().isBlank();
            if (!pending) {
                continue;
            }
            try {
                assignments.resendPendingActivationCode(assignment.getId(), RESEND_COOLDOWN);
            } catch (RuntimeException e) {
                log.warn("Activation re-send failed for assignment {}: {}",
                        assignment.getId(), e.getClass().getSimpleName());
            }
        }
    }

    @WriteAudited(skip = true, reason = "emits LOGOUT / TOKEN_REFRESH itself, or is a check that mutates nothing")
    @PostMapping("/logout")
    @Operation(summary = "Logout current user",
        description = "Public. Revokes the bearer access token when one is sent, and every valid refresh "
            + "token presented — the HttpOnly cookie (web) and the optional body field "
            + "{\"refreshToken\": \"...\"} (mobile) — then clears the refresh cookie. With a bearer, "
            + "a refresh token of another user is left alone. Always 200.")
    @ApiResponse(responseCode = "200", description = "Logout successful", content = @Content(schema = @Schema(implementation = MessageResponse.class)))
    public ResponseEntity<Object> logout(
            @RequestBody(required = false) java.util.Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        // Blacklist the current access token so it cannot be reused
        String bearerToken = request.getHeader("Authorization");
        String username = null;
        if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
            String jwt = bearerToken.substring(7);
            try {
                String jti = jwtTokenProvider.getJtiFromToken(jwt);
                long expMs = jwtTokenProvider.getExpiration(jwt).getTime();
                username = jwtTokenProvider.getUsernameFromJWT(jwt);
                if (jti != null) {
                    tokenBlacklistService.blacklist(jti, expMs);
                    log.info("[LOGOUT] Blacklisted access token jti={}", jti);

                    auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                            .eventType(AuditEventType.TOKEN_REVOKED)
                            .eventDescription("Access token revoked on logout (jti=" + jti + ")")
                            .ipAddress(request.getRemoteAddr())
                            .status(AuditStatus.SUCCESS)
                            .userName(username)
                            .build());
                }
            } catch (Exception ex) {
                log.debug("[LOGOUT] Could not extract jti from token: {}", ex.getMessage());
            }
        }
        // Revoke the refresh token too. Without this a signed-out session
        // was only half closed: the access token was blacklisted, but the
        // refresh token — which the mobile apps hold themselves and send in
        // the body — could mint a fresh pair until it expired.
        // Both sources, not one or the other: the Android client's cookie jar
        // keeps the refresh cookie from login while it rotates the refresh
        // token it actually uses in the body, so the cookie can be stale.
        String cookieRefresh = refreshTokenCookieService.read(request);
        String bodyRefresh = body != null ? body.get("refreshToken") : null;
        revokeRefreshTokenOnLogout(cookieRefresh, username, request.getRemoteAddr());
        if (bodyRefresh != null && !bodyRefresh.equals(cookieRefresh)) {
            revokeRefreshTokenOnLogout(bodyRefresh, username, request.getRemoteAddr());
        }
        // S-01: clear the HttpOnly refresh-token cookie
        refreshTokenCookieService.clear(response);
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(new MessageResponse("Logged out successfully."));
    }

    /**
     * Blacklist a refresh token presented at logout.
     *
     * <p>A valid refresh token is revoked on its own: holding it already lets
     * the holder mint tokens with it, so revoking it grants nothing, and an
     * idle client arrives here with an EXPIRED access token (no bearer
     * identity) and must still be able to close its session. When the bearer
     * did identify a user, a refresh token belonging to someone else is left
     * alone, so logout cannot sign another person out. An expired or
     * unparseable refresh token needs no revocation and is ignored.
     */
    private void revokeRefreshTokenOnLogout(String refreshToken, String bearerUsername, String ip) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            if (!jwtTokenProvider.validateToken(refreshToken)) {
                return;
            }
            String subject = jwtTokenProvider.getUsernameFromJWT(refreshToken);
            if (bearerUsername != null && !bearerUsername.equals(subject)) {
                log.warn("[LOGOUT] Refresh token not revoked: it belongs to a different user than the bearer");
                return;
            }
            String jti = jwtTokenProvider.getJtiFromToken(refreshToken);
            if (jti == null) {
                return;
            }
            tokenBlacklistService.blacklist(jti, jwtTokenProvider.getExpiration(refreshToken).getTime());
            log.info("[LOGOUT] Blacklisted refresh token jti={}", jti);
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.TOKEN_REVOKED)
                    .eventDescription("Refresh token revoked on logout (jti=" + jti + ")")
                    .ipAddress(ip)
                    .status(AuditStatus.SUCCESS)
                    .userName(subject)
                    .build());
        } catch (Exception ex) {
            log.debug("[LOGOUT] Could not revoke the refresh token: {}", ex.getMessage());
        }
    }

    /**
     * Why this token may not mint a session, or {@code null} when it may
     * go on: not a refresh token, issued before a force-logout-all, or
     * already spent (superseded by a sibling tab: 409; replayed: 401).
     */
    private ResponseEntity<Object> refreshRefusal(String refreshToken) {
        // A refresh token, and nothing else the same key signs: the MFA
        // challenge token would mint a session past MFA, an impersonation
        // token an ordinary session that outlives the impersonation, and an
        // access token a session that never ends.
        if (!jwtTokenProvider.isRefreshToken(refreshToken)) {
            log.warn("🔄 [REFRESH] Invalid or expired refresh token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(REFRESH_INVALID_MESSAGE));
        }

        // Force-logout-all revokes refresh tokens too: one issued before it
        // must not mint a new session (GlobalSessionRevocationService#revokes,
        // the rule the filter applies to access tokens).
        // The fresh read, not the 30-second cache: an instance that has not
        // caught up would otherwise mint a post-cut-off pair from a revoked
        // token, which then outlives the kill switch. Refresh is rare.
        if (globalSessionRevocationService.revokesFresh(jwtTokenProvider.getIssuedAt(refreshToken))) {
            log.warn("🔄 [REFRESH] Refused — refresh token predates the last force-logout-all");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(REFRESH_INVALID_MESSAGE));
        }

        // Replay detection: an already-used refresh token is refused here; the
        // atomic claim below settles a race between two concurrent uses. Used
        // a moment ago by another tab of the same browser: superseded (409),
        // not replayed, so that tab retries with the rotated cookie instead
        // of signing every tab out.
        String refreshJti = jwtTokenProvider.getJtiFromToken(refreshToken);
        if (refreshJti != null && tokenBlacklistService.isBlacklisted(refreshJti)) {
            if (tokenBlacklistService.wasRecentlyRotated(refreshJti)) {
                return refreshSuperseded(refreshJti);
            }
            log.warn("🔄 [REFRESH] Replay detected — refresh token jti={} already used", refreshJti);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Refresh token has already been used. Please log in again."));
        }
        return null;
    }

    /**
     * How long after a rotation another use of the old refresh token counts as
     * a sibling tab that lost the race (409, retry) rather than a replay (401).
     */
    static final long REFRESH_ROTATION_GRACE_MS = 30_000L;

    /** The 409: a readable sentence (clients show it); the status is what a client acts on. */
    private static final String REFRESH_SUPERSEDED_MESSAGE =
            "This session was just refreshed by another tab or request. Retry with the latest token.";

    private static final String REFRESH_INVALID_MESSAGE =
            "Refresh token is invalid or has expired. Please log in again.";

    /** A provider user's refresh token without a (current) second factor: sign in, with the code. */
    private static final String REFRESH_SECOND_FACTOR_KEY = "auth.refresh.secondFactorRequired";

    /** The HttpOnly cookie first; the body is the legacy fallback (S-01). */
    private String readRefreshToken(HttpServletRequest request, java.util.Map<String, String> body) {
        String fromCookie = refreshTokenCookieService.read(request);
        if (fromCookie != null) {
            return fromCookie;
        }
        return body != null ? body.get("refreshToken") : null;
    }

    /** A provider user whose refresh token carries no (or an expired) second factor. */
    private boolean lapsedProviderFactor(java.util.UUID userId, String username, java.time.Instant secondFactor) {
        return secondFactor == null && isProviderUserChallenged(userId, username);
    }

    /** Another tab rotated this refresh token a moment ago: no tokens, retry with the new cookie. */
    private ResponseEntity<Object> refreshSuperseded(String jti) {
        // WARN, like a replay: a sibling tab looks exactly like a stolen copy
        // replayed inside the grace, and theft alerting must still see it.
        log.warn("🔄 [REFRESH] Superseded (or a replay within the grace) — refresh token jti={} was just rotated", jti);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MessageResponse(REFRESH_SUPERSEDED_MESSAGE));
    }

    /**
     * Silent token refresh.
     *
     * <p>Accepts a still-valid refresh token and issues a new short-lived access token.
     * The refresh token itself is also rotated so each use produces a fresh pair.
     * This endpoint is intentionally <strong>public</strong> (no JWT required) because
     * it is called precisely when the access token has expired.
     *
     * <p>Security rationale: the refresh token is signed with the same HMAC-SHA secret and
     * carries its own {@code exp} claim, so a tampered or expired refresh token is rejected
     * by {@link JwtTokenProvider#isRefreshToken}, which also refuses every other token the
     * same key signs (access, impersonation, MFA challenge).
     */
    @WriteAudited(skip = true, reason = "emits LOGOUT / TOKEN_REFRESH itself, or is a check that mutates nothing")
    @PostMapping("/token/refresh")
    @Operation(
        summary = "Refresh access token",
        description = "Exchange a valid refresh token for a new access token + rotated refresh token.")
    @ApiResponse(responseCode = "200", description = "Tokens refreshed")
    @ApiResponse(responseCode = "401", description = "Refresh token missing, invalid, or expired")
    @ApiResponse(responseCode = "409", description = "Superseded: another tab just rotated this refresh token; retry")
    public ResponseEntity<Object> refreshToken(
            @RequestBody(required = false) java.util.Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        // KC-5 prep: once the soak flag is on, no new access tokens are
        // minted from refresh tokens either. Existing access tokens keep
        // working until they expire; after that clients must re-authenticate
        // via Keycloak.
        if (authProps.oidcRequired()) {
            log.warn("🔄 [REFRESH] Rejected — legacy issuer disabled (app.auth.oidc.required=true)");
            return legacyIssuerGone(
                    "Legacy token refresh is disabled. Sign in via Single Sign-On.");
        }

        // S-01: prefer the HttpOnly refresh cookie over a body-borne refresh token.
        // Body-based refresh remains supported as a legacy fallback during rollout.
        String refreshToken = readRefreshToken(request, body);
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("🔄 [REFRESH] Missing refresh token (cookie + body both empty)");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Refresh token is required."));
        }

        ResponseEntity<Object> refusal = refreshRefusal(refreshToken);
        if (refusal != null) {
            return refusal;
        }
        String refreshJti = jwtTokenProvider.getJtiFromToken(refreshToken);

        String username = jwtTokenProvider.getUsernameFromJWT(refreshToken);
        var user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isActive()) {
            log.warn("🔄 [REFRESH] User '{}' not found or inactive", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User account not found or inactive."));
        }
        // QA D114: a refresh token minted before the last password change mints nothing.
        if (com.example.hms.security.PasswordChangeCutoff.predates(
                jwtTokenProvider.getIssuedAt(refreshToken), user.getPasswordChangedAt())) {
            log.warn("🔄 [REFRESH] Refused — refresh token predates the last password change");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(REFRESH_INVALID_MESSAGE));
        }

        // Closes Copilot review #4 on PR #224 — refuse to mint a new
        // super-admin access token from the surviving refresh cookie while
        // an impersonation session is active for this user. Without this
        // check, a 401 on the impersonation token would auto-trigger this
        // refresh path and silently elevate the caller back to super-admin
        // without ever emitting an IMPERSONATION_ENDED audit event.
        if (impersonationSessionTracker.hasActive(user.getId())) {
            log.warn("🔄 [REFRESH] Refused — user '{}' has an active impersonation session", username);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse(
                        "Active support-impersonation session — call /super-admin/impersonation/stop before refreshing."));
        }

        // Resolve current roles from tenant assignments (authoritative source)
        List<String> roles = assignmentRepository.findByUser(user).stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .map(a -> a.getRole().getCode())
                .distinct()
                .toList();

        // ── Idle session timeout gate (v1.0 row 7) ───────────────────────
        // Refusing refresh-while-idle is the spec invariant: a silent
        // refresh of an idle session would defeat the timeout entirely.
        // Gate sits AFTER the impersonation + role-resolution checks so
        // the role list is available for the machine-role carve-out, and
        // BEFORE token rotation so a rejected refresh does not extend the
        // window via the post-rotation touch below.
        var refreshAuthorities = roles.stream()
                .<org.springframework.security.core.GrantedAuthority>map(
                    org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                .toList();
        if (idleSessionGate.shouldReject(user.getId(), refreshAuthorities)) {
            log.warn("🔄 [REFRESH] Refused — user '{}' has been idle past the configured window", username);
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.TOKEN_REFRESH)
                    .eventDescription("Refresh refused — idle timeout")
                    .userName(username)
                    .userId(user.getId())
                    .status(AuditStatus.FAILURE)
                    .build());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate",
                        com.example.hms.security.IdleSessionGate.WWW_AUTHENTICATE_CHALLENGE)
                    .body(new MessageResponse(
                        "Session timed out due to inactivity. Please log in again."));
        }

        // Spend the refresh token atomically BEFORE minting: of two concurrent
        // refreshes with one token, exactly one gets a new pair. The other is
        // superseded (409): it gets no tokens and retries with the cookie the
        // winner rotated, so two tabs refreshing at once stay signed in.
        if (refreshJti == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(REFRESH_INVALID_MESSAGE));
        }
        // Rotated is recorded BEFORE the token is spent: a sibling tab that
        // sees it spent always sees it rotated too, so it is told
        // "superseded" (409, retry), never "replayed" (401, sign out). Marking
        // a token we then fail to claim is harmless: it only turns another
        // use within the grace from 401 into 409, and issues nothing.
        tokenBlacklistService.markRotated(refreshJti, REFRESH_ROTATION_GRACE_MS);
        if (!tokenBlacklistService.claim(refreshJti, jwtTokenProvider.getExpiration(refreshToken).getTime())) {
            return refreshSuperseded(refreshJti);
        }

        // Claimed and marked rotated above: if anything below fails before
        // the new pair is out, give the old token back, or the session is
        // stranded (every retry would read "superseded" until it expires).
        try {
            var descriptor = new com.example.hms.security.TokenUserDescriptor(user.getId(), username, roles);
            // A refresh token minted after a verified TOTP code passes its second
            // factor on, with the time it was verified, until that is older than
            // the max age (AC-13); a password-only one never gains it.
            java.time.Instant secondFactor = jwtTokenProvider.secondFactorAt(refreshToken);
            // A provider user whose factor has lapsed (older than the max age)
            // would get a pair every call refuses (403 mfa.enrollment.required)
            // with nothing in the portal to recover: refuse the refresh
            // instead, so the normal sign-in and challenge take over.
            if (lapsedProviderFactor(user.getId(), username, secondFactor)) {
                // Left spent, not released: this token can never yield a pair again.
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                        .eventType(AuditEventType.TOKEN_REFRESH)
                        .eventDescription("Refresh refused — provider second factor missing or expired")
                        .userName(username)
                        .userId(user.getId())
                        .status(AuditStatus.FAILURE)
                        .build());
                // The cookie can never yield a pair again: drop it, so the
                // portal's silent refresh stops retrying and signs in instead.
                refreshTokenCookieService.clear(response);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse(MessageUtil.resolve(REFRESH_SECOND_FACTOR_KEY)));
            }
            String newAccessToken  = jwtTokenProvider.generateAccessToken(descriptor, secondFactor);

            // Rotate the refresh token so each use yields a fresh one
            var refreshAuth = new UsernamePasswordAuthenticationToken(
                    new com.example.hms.security.CustomUserDetails(user,
                            roles.stream()
                                 .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                                 .collect(java.util.stream.Collectors.toSet())),
                    null,
                    roles.stream()
                         .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                         .toList());
            String newRefreshToken = jwtTokenProvider.generateRefreshToken(refreshAuth, secondFactor);

            // ── Idle session timeout — touch on successful refresh ──────────
            // Only after the rotation has succeeded; touching pre-rotation
            // would extend the window even if rotation failed downstream.
            idleSessionGate.touchIfHuman(user.getId(), refreshAuthorities);

            long nowMs      = System.currentTimeMillis();
            long accessExp  = jwtTokenProvider.getExpiration(newAccessToken).getTime();
            long refreshExp = jwtTokenProvider.getExpiration(newRefreshToken).getTime();

            log.info("🔄 [REFRESH] Tokens rotated for user='{}'", username);

            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.TOKEN_REFRESH)
                    .eventDescription("Token pair rotated (old refresh jti=" + refreshJti + ")")
                    .userName(username)
                    .userId(user.getId())
                    .status(AuditStatus.SUCCESS)
                    .build());

            // Include fresh hospital context so the frontend can re-hydrate
            var hospitalContext = resolveHospitalContext(user.getId());

            // S-01: rotate the HttpOnly refresh-token cookie
            writeRefreshCookie(response, newRefreshToken);

            return ResponseEntity.ok(JwtResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(newRefreshToken)
                    .issuedAt(nowMs)
                    .accessTokenExpiresAt(accessExp)
                    .refreshTokenExpiresAt(refreshExp)
                    .id(user.getId())
                    .username(user.getUsername())
                    .roles(roles)
                    .primaryHospitalId(hospitalContext.primaryHospitalId())
                    .primaryHospitalName(hospitalContext.primaryHospitalName())
                    .hospitalIds(hospitalContext.hospitalIds())
                    .build());
        } catch (RuntimeException mintFailed) {
            tokenBlacklistService.release(refreshJti);
            throw mintFailed;
        }
    }

    /**
     * Verify current user's password without issuing new tokens.
     * Used by the lock-screen to re-authenticate when the session is idle.
     * Requires a valid JWT (user is already logged in but screen is locked).
     */
    @WriteAudited(skip = true, reason = "emits LOGOUT / TOKEN_REFRESH itself, or is a check that mutates nothing")
    @PostMapping("/verify-password")
    @Operation(summary = "Verify password for screen unlock",
               description = "Validates the current user's password. Returns 200 if correct, 401 if wrong. "
                           + "Does NOT issue new tokens — the existing JWT remains valid.")
    @ApiResponse(responseCode = "200", description = "Password verified")
    @ApiResponse(responseCode = "401", description = "Invalid password")
    public ResponseEntity<Object> verifyPassword(@Valid @RequestBody LoginRequest request) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        // Security: only allow verifying the password for the currently authenticated user
        if (!currentUsername.equalsIgnoreCase(request.getUsername())) {
            log.warn("🔒 [VERIFY-PWD] Username mismatch: authenticated='{}' requested='{}'",
                    currentUsername, request.getUsername());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Cannot verify password for a different user."));
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
            log.info("🔒 [VERIFY-PWD] Password verified for user='{}'", currentUsername);
            return ResponseEntity.ok(new MessageResponse("Password verified."));
        } catch (BadCredentialsException ex) {
            log.warn("🔒 [VERIFY-PWD] Wrong password for user='{}'", currentUsername);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.verifyPassword.invalid")));
        } catch (DisabledException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.account.disabled")));
        } finally {
            resetSecurityContextToAnonymous();
        }
    }

    /**
     * Change the authenticated user's own password.
     * Clears the {@code forcePasswordChange} flag on success so subsequent
     * logins return {@code forcePasswordChange: false}.
     */
    @PostMapping("/me/change-password")
    @Operation(summary = "Change own password", description = "Allows any authenticated user to change their own password. Clears the force-password-change flag.")
    @ApiResponse(responseCode = "200", description = "Password changed successfully")
    @ApiResponse(responseCode = "400", description = "New password too short or same as current")
    @ApiResponse(responseCode = "401", description = "Current password incorrect")
    public ResponseEntity<Object> changeOwnPassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(NOT_AUTHENTICATED));
        }
        String username = authentication.getName();
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(USER_NOT_FOUND));
        }
        var user = userOpt.get();

        // Verify the user knows their current credentials (prevents CSRF token theft)
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.currentPassword()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(MessageUtil.resolve("auth.password.currentIncorrect")));
        } finally {
            resetSecurityContextToAnonymous();
            // Restore the original authentication after verify sub-call
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        if (request.newPassword() == null || request.newPassword().length() < 8) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.password.tooShort")));
        }
        if (request.newPassword().equals(request.currentPassword())) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.password.sameAsCurrent")));
        }

        // Password history check (T-20) — reject reuse of last 5 passwords
        if (passwordHistoryService.isPasswordReused(user.getId(), request.newPassword())) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.PASSWORD_HISTORY_VIOLATION)
                    .eventDescription("Password change rejected — matches a recent password")
                    .userName(username)
                    .userId(user.getId())
                    .status(AuditStatus.FAILURE)
                    .build());
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.password.reused")));
        }

        // Update password, clear force-change flag, and record in history
        userService.changeOwnPassword(user.getId(), request.newPassword());

        log.info("🔑 [CHANGE-PWD] Password changed for userId={}; forcePasswordChange cleared.", user.getId());
        return ResponseEntity.ok(new MessageResponse(MessageUtil.resolve("auth.password.changed")));
    }

    /** Request body for {@code POST /auth/me/change-password}. */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {}

    /**
     * Change the authenticated user's own username.
     * Clears the {@code forceUsernameChange} flag on success.
     */
    @PostMapping("/me/change-username")
    @Operation(summary = "Change own username", description = "Allows any authenticated user to change their own username. Clears the force-username-change flag.")
    @ApiResponse(responseCode = "200", description = "Username changed successfully")
    @ApiResponse(responseCode = "400", description = "Username invalid or already taken")
    public ResponseEntity<Object> changeOwnUsername(
            @Valid @RequestBody ChangeUsernameRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(NOT_AUTHENTICATED));
        }
        String currentUsername = authentication.getName();
        var userOpt = userRepository.findByUsername(currentUsername);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(USER_NOT_FOUND));
        }
        var user = userOpt.get();

        String newUsername = request.newUsername() == null ? "" : request.newUsername().trim();
        if (newUsername.length() < 3 || newUsername.length() > com.example.hms.model.User.USERNAME_MAX_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.username.length")));
        }
        if (!newUsername.matches("^[a-zA-Z0-9._-]+$")) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.username.characters")));
        }
        if (newUsername.equals(currentUsername)) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(MessageUtil.resolve("auth.username.sameAsCurrent")));
        }

        try {
            userService.changeOwnUsername(user.getId(), newUsername);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(ex.getMessage()));
        }

        log.info("🔑 [CHANGE-USR] Username changed for user='{}' -> '{}'; forceUsernameChange cleared.",
                currentUsername, newUsername);
        return ResponseEntity.ok(new MessageResponse(MessageUtil.resolve("auth.username.changed")));
    }

    /** Request body for {@code POST /auth/me/change-username}. */
    public record ChangeUsernameRequest(@NotBlank String newUsername) {}

    /**
     * The message key for what is wrong with the new password of a forced
     * setup, or {@code null}. A reused password is audited (ids only).
     */
    private String setupPasswordProblem(UUID userId, CompleteSetupRequest request) {
        String newPassword = request.newPassword();
        if (newPassword == null || newPassword.isBlank()) {
            return "account.setup.password.required";
        }
        if (newPassword.length() < 8) {
            return "account.setup.password.length";
        }
        if (newPassword.equals(request.currentPassword())) {
            return "account.setup.password.same";
        }
        if (passwordHistoryService.isPasswordReused(userId, newPassword)) {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.PASSWORD_HISTORY_VIOLATION)
                    .eventDescription("Password change rejected — matches a recent password")
                    .userId(userId)
                    .status(AuditStatus.FAILURE)
                    .build());
            return "account.setup.password.reused";
        }
        return null;
    }

    /**
     * The forced first sign-in (D17), in ONE call: the new username (when the
     * account must change it) and the new password (when it must change it)
     * are validated first and then applied in one transaction. The two calls
     * it replaces could not be made in sequence: the rename invalidated the
     * access token (its subject is the username), so the session ended before
     * the password call and the account kept its temporary password. Each
     * forced flag clears only with its own change. The client signs out after
     * success and the holder signs in with the new details.
     */
    @PostMapping("/me/complete-setup")
    @Operation(summary = "Complete the forced first sign-in",
            description = "Changes the username and the password together when the account is forced to change them.")
    @ApiResponse(responseCode = "200", description = "Account set up; sign in again with the new details")
    @ApiResponse(responseCode = "400", description = "A value is invalid, the current password is wrong, or nothing is pending")
    public ResponseEntity<Object> completeAccountSetup(@Valid @RequestBody CompleteSetupRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(NOT_AUTHENTICATED));
        }
        String username = authentication.getName();
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(USER_NOT_FOUND));
        }
        var user = userOpt.get();
        boolean mustRename = user.isForceUsernameChange();
        boolean mustChangePassword = user.isForcePasswordChange();
        if (!mustRename && !mustChangePassword) {
            return badSetup("account.setup.nothing");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.currentPassword()));
        } catch (BadCredentialsException ex) {
            // 400, not 401: the session is fine, and the portal treats a 401
            // as an expired session (refresh, then sign-out).
            return badSetup("account.setup.password.current");
        } finally {
            resetSecurityContextToAnonymous();
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        String newUsername = mustRename ? Objects.toString(request.newUsername(), "").trim() : null;
        String problem = mustRename ? usernameProblem(newUsername) : null;
        if (problem == null && mustChangePassword) {
            problem = setupPasswordProblem(user.getId(), request);
        }
        if (problem != null) {
            return badSetup(problem);
        }
        String newPassword = mustChangePassword ? request.newPassword() : null;

        try {
            userService.completeAccountSetup(user.getId(), newUsername, newPassword);
        } catch (BusinessException ex) {
            return ResponseEntity.badRequest().body(new MessageResponse(ex.getMessage()));
        }
        log.info("🔑 [ACCOUNT-SETUP] Completed for user={}", user.getId());
        return ResponseEntity.ok(new MessageResponse(MessageUtil.resolve("account.setup.done")));
    }

    /** The bundle key for what is wrong with a chosen username, or {@code null}. */
    private static String usernameProblem(String newUsername) {
        if (newUsername.isEmpty()) {
            return "account.setup.username.required";
        }
        if (newUsername.length() < 3 || newUsername.length() > com.example.hms.model.User.USERNAME_MAX_LENGTH) {
            return "account.setup.username.length";
        }
        if (!newUsername.matches("^[a-zA-Z0-9._-]+$")) {
            return "account.setup.username.chars";
        }
        // Keeping the current username is a valid choice (QA D52): the forced
        // step asks the holder to confirm a name they will sign in with, and
        // the one they were given may already suit them. Only the format is
        // checked; the service clears the flag either way.
        return null;
    }

    private static ResponseEntity<Object> badSetup(String key) {
        return ResponseEntity.badRequest().body(new MessageResponse(MessageUtil.resolve(key)));
    }

    /** Request body for {@code POST /auth/me/complete-setup}. */
    public record CompleteSetupRequest(
            String newUsername,
            @NotBlank String currentPassword,
            String newPassword) {}

    @PostMapping("/request-reset")
    public ResponseEntity<Void> legacyRequestPasswordReset(
            @RequestParam("email") @Email @NotBlank String email,
            Locale locale,
            HttpServletRequest request) {
        String ip = clientIp(request);
        try {
            authNotification.passwordReset().requestReset(email, locale, ip);
        } catch (ResourceNotFoundException e) {
            log.debug("Password reset requested for an unknown address");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> legacyConfirmPasswordReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        try {
            authNotification.passwordReset().confirmReset(dto.getToken(), dto.getNewPassword());
        } catch (ResourceNotFoundException | IllegalStateException e) {
            log.debug("Password reset confirm failed: {}", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // Diagnostic endpoint — restricted to SUPER_ADMIN only (T-15 security hardening)
    @GetMapping("/token/echo")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Object> echoToken(@RequestHeader(value = "Authorization", required = false) String authz) {
        if (authz == null || !authz.startsWith(BEARER_PREFIX)) {
            return ResponseEntity.badRequest().body(new MessageResponse("Missing or invalid Authorization header"));
        }
        String token = authz.substring(BEARER_PREFIX.length());
        try {
            boolean valid = jwtTokenProvider.validateToken(token);
            var roles = valid ? jwtTokenProvider.getRolesFromToken(token) : null;
            return ResponseEntity.ok(java.util.Map.of(
                    "valid", valid,
                    "length", token.length(),
                    "roles", roles,
                    "subject", valid ? jwtTokenProvider.getUsernameFromJWT(token) : null));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(400).body(java.util.Map.of(
                    "valid", false,
                    "error", ex.getMessage()));
        }
    }

    // Lightweight connectivity / readiness probe (no DB access beyond basic
    // controller dispatch)
    @GetMapping("/ping")
    public ResponseEntity<Object> ping() {
        return ResponseEntity.ok(new MessageResponse("pong"));
    }

    /**
     * CSRF token bootstrap endpoint.
     *
     * Angular (or any SPA) must call {@code GET /auth/csrf-token} at application
     * startup (before the first mutating request). Spring Security writes the
     * {@code XSRF-TOKEN} cookie on this response. Subsequent POST/PUT/PATCH/DELETE
     * requests must echo the cookie value back via the {@code X-XSRF-TOKEN} header
     * (handled by {@code CsrfInterceptor} on the frontend).
     *
     * The endpoint is publicly accessible (no authentication required) because
     * it must work before login.
     */
    @Operation(
        summary = "CSRF Token Bootstrap",
        description = "Returns an empty 204 response that causes Spring Security to set the XSRF-TOKEN cookie. " +
                      "Call this once at SPA startup before any state-mutating requests.",
        responses = @ApiResponse(responseCode = "204", description = "CSRF cookie issued.")
    )
    @GetMapping("/csrf-token")
    public ResponseEntity<Void> csrfToken(
            HttpServletRequest request,
            HttpServletResponse response) {
        // Accessing the CsrfToken attribute forces Spring Security's
        // CookieCsrfTokenRepository to write the XSRF-TOKEN Set-Cookie header.
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            // Touch the token to ensure the deferred token is loaded and cookie is written.
            token.getToken();
        }
        return ResponseEntity.noContent().build();
    }

    private static String clientIp(HttpServletRequest request) {
        String h = request.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            int comma = h.indexOf(',');
            return comma > 0 ? h.substring(0, comma).trim() : h.trim();
        }
        h = request.getHeader("X-Real-IP");
        return (h != null && !h.isBlank()) ? h.trim() : request.getRemoteAddr();
    }

    @GetMapping("/credentials/me")
    public ResponseEntity<UserCredentialHealthDTO> getCredentialHealthForCurrentUser() {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UserCredentialHealthDTO dto = userCredentialLifecycleService.getCredentialHealth(userId);
        return ResponseEntity.ok(dto);
    }

    /**
     * Rewrites the caller's MFA records, which can disable or delete a verified
     * authenticator: while one is active, that takes the step-up in
     * {@code X-Mfa-Token} (a current code or an unused backup code; 403 with a
     * reason code otherwise), checked in the same transaction as the change.
     */
    @PutMapping("/credentials/mfa")
    public ResponseEntity<List<UserMfaEnrollmentDTO>> updateMfaEnrollments(
            @Valid @RequestBody List<UserMfaEnrollmentRequestDTO> payload,
            @RequestHeader(value = "X-Mfa-Token", required = false) String stepUpCode) {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<UserMfaEnrollmentDTO> response = userCredentialLifecycleService.upsertOwnMfaEnrollments(
                userId, payload, SecurityContextHolder.getContext().getAuthentication(), stepUpCode);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/credentials/recovery")
    public ResponseEntity<List<UserRecoveryContactDTO>> updateRecoveryContacts(
            @Valid @RequestBody List<UserRecoveryContactRequestDTO> payload) {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<UserRecoveryContactDTO> response = userCredentialLifecycleService.upsertRecoveryContacts(userId, payload);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/credentials/recovery/{contactId}/send-code")
    public ResponseEntity<java.util.Map<String, String>> sendRecoveryContactVerificationCode(
            @PathVariable UUID contactId) {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String contactType = userCredentialLifecycleService.sendRecoveryContactVerificationCode(userId, contactId);
        return ResponseEntity.ok(java.util.Map.of("message", "Verification code sent", "contactType", contactType));
    }

    @PostMapping("/credentials/recovery/{contactId}/verify")
    public ResponseEntity<UserRecoveryContactDTO> verifyRecoveryContact(
            @PathVariable UUID contactId,
            @Valid @RequestBody VerifyRecoveryContactRequest request) {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UserRecoveryContactDTO dto = userCredentialLifecycleService.verifyRecoveryContact(
                userId, contactId, request.getCode());
        return ResponseEntity.ok(dto);
    }

    private UUID resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String identifier = authentication.getName();
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return userRepository
            .findFirstByUsernameIgnoreCaseOrEmailIgnoreCaseOrPhoneNumber(identifier, identifier, identifier)
            .map(com.example.hms.model.User::getId)
            .orElse(null);
    }

    private void resetSecurityContextToAnonymous() {
        SecurityContextHolder.clearContext();
        var anonymousContext = SecurityContextHolder.createEmptyContext();
        anonymousContext.setAuthentication(new AnonymousAuthenticationToken(
                "anonymousUser",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        SecurityContextHolder.setContext(anonymousContext);
    }

    // helper methods removed as self-registration is deprecated

    // ── WebSocket ticket (T-37) ──

    /**
     * Issue a single-use, 1-minute ticket for WebSocket handshake authentication.
     * The client passes this as {@code ?ticket=<value>} on the {@code /ws-chat} endpoint
     * instead of sending the JWT as a query parameter.
     */
    @WriteAudited(skip = true, reason = "emits LOGOUT / TOKEN_REFRESH itself, or is a check that mutates nothing")
    @PostMapping("/ws-ticket")
    public ResponseEntity<Map<String, String>> issueWsTicket(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        String username = authentication.getName();
        String ticket = wsTicketService.issue(username);
        return ResponseEntity.ok(Map.of("ticket", ticket));
    }

    /**
     * Session bootstrap — resolves authoritative hospital/permission context from the DB.
     *
     * <p>Replaces client-side JWT claim decoding for hospital_id. The frontend
     * (and mobile apps) should call this once after receiving a valid token so
     * all hospital-context decisions are based on the DB, not the token payload.
     *
     * <p>Side-effect: when {@code authSource == "keycloak"}, updates
     * {@code lastOidcLoginAt} on the user record.
     *
     * @return {@link SessionBootstrapResponseDTO} with identity, roles, and hospital context
     */
    @Operation(
        summary = "Session Bootstrap",
        description = "Returns authoritative session context (roles, hospital, staff/patient profile) "
                    + "from the DB for the currently authenticated principal. "
                    + "Updates lastOidcLoginAt when authSource is 'keycloak'.")
    @ApiResponse(responseCode = "200", description = "Session context resolved",
        content = @Content(schema = @Schema(implementation = SessionBootstrapResponseDTO.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    @GetMapping("/session/bootstrap")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionBootstrapResponseDTO> sessionBootstrap(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String username = authentication.getName();
        log.debug("[SESSION-BOOTSTRAP] Resolving session context for user='{}'", username);
        SessionBootstrapResponseDTO response = authBootstrapService.resolveCurrentSession(username);
        // AC-13: a provider user whose token carries no second factor is told
        // so here; every other request answers 403 mfa.enrollment.required.
        ProviderMfaGate gate = providerMfaGate.getIfAvailable();
        response.setMfaEnrollmentRequired(response.isProviderUser()
                && gate != null && !gate.secondFactorPresented(authentication));
        return ResponseEntity.ok(response);
    }

    /* ── Hospital assignment context ── */
    private record HospitalContext(UUID primaryHospitalId, String primaryHospitalName,
                                   List<UUID> hospitalIds) {}

    /**
     * Resolve hospital context from the user's active tenant role assignments.
     * Returns the primary hospital (first active assignment) and all permitted hospital IDs.
     */
    private boolean isMfaRequiredForUser(List<String> roles) {
        List<String> required = authProps.mfaRequiredRoles();
        if (required.isEmpty()) {
            return false;
        }
        return roles.stream().anyMatch(required::contains);
    }

    /** The account holds a live provider assignment, so its login is challenged (AC-13). */
    private boolean isProviderUserChallenged(UUID userId, String username) {
        ProviderMfaGate gate = providerMfaGate.getIfAvailable();
        return gate != null && gate.isProviderUser(userId, username);
    }

    /**
     * Roadmap row 8 — uniform 410 Gone response for the two legacy
     * issuer endpoints. Adds an RFC 8414 {@code Link: rel="oauth2-issuer"}
     * header pointing at the OIDC discovery document so clients can
     * self-discover the SSO endpoint without operator intervention. The
     * header is only emitted when {@code app.auth.oidc.issuer-uri} is
     * non-empty — local dev runs without OIDC configured still get the
     * runbook copy in the JSON body.
     */
    private ResponseEntity<Object> legacyIssuerGone(String userFacingMessage) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.GONE);
        String issuer = authProps.oidcIssuerUri();
        if (!issuer.isEmpty()) {
            String discovery = issuer.endsWith("/")
                    ? issuer + ".well-known/openid-configuration"
                    : issuer + "/.well-known/openid-configuration";
            builder = builder.header("Link",
                    "<" + discovery + ">; rel=\"oauth2-issuer\"; type=\"application/json\"");
        }
        return builder.body(new MessageResponse(userFacingMessage));
    }

    /**
     * S-01: writes the refresh token into an HttpOnly cookie so it is not
     * accessible from browser JavaScript. MaxAge is derived from the token's
     * own {@code exp} claim so the cookie expires in lockstep with the JWT.
     */
    private void writeRefreshCookie(HttpServletResponse response, String refreshToken) {
        if (response == null || refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            long expMs = jwtTokenProvider.getExpiration(refreshToken).getTime();
            long maxAgeMs = Math.max(0L, expMs - System.currentTimeMillis());
            refreshTokenCookieService.write(response, refreshToken, maxAgeMs);
        } catch (Exception ex) {
            log.warn("Failed to set refresh cookie: {}", ex.getMessage());
        }
    }

    private HospitalContext resolveHospitalContext(UUID userId) {
        var assignments = assignmentRepository.findAllDetailedByUserId(userId).stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()) && a.getHospital() != null)
                .toList();

        List<UUID> ids = assignments.stream()
                .map(a -> a.getHospital().getId())
                .distinct()
                .toList();

        UUID primaryId = ids.isEmpty() ? null : ids.get(0);
        String primaryName = assignments.stream()
                .filter(a -> a.getHospital().getId().equals(primaryId))
                .map(a -> a.getHospital().getName())
                .findFirst()
                .orElse(null);

        return new HospitalContext(primaryId, primaryName, ids.isEmpty() ? null : ids);
    }

}
