package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.BootstrapSignupRequest;
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
import com.example.hms.controller.support.AuthNotificationFacade;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.service.PasswordHistoryService;
import com.example.hms.service.MfaService;
import com.example.hms.security.WsTicketService;
import com.example.hms.security.TokenBlacklistService;
import com.example.hms.service.AuditEventLogService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@Slf4j
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final UserRepository userRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthNotificationFacade authNotification;

    private final UserService userService;
    private final UserCredentialLifecycleService userCredentialLifecycleService;
    private final TokenBlacklistService tokenBlacklistService;
    private final LoginAttemptService loginAttemptService;
    private final AuditEventLogService auditEventLogService;
    private final PasswordHistoryService passwordHistoryService;
    private final MfaService mfaService;
    private final WsTicketService wsTicketService;
    private final String frontendBaseUrl;
    private final List<String> mfaRequiredRoles;

    public AuthController(UserRepository userRepository,
            UserRoleHospitalAssignmentRepository assignmentRepository,
            UserService userService,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            AuthNotificationFacade authNotification,
            UserCredentialLifecycleService userCredentialLifecycleService,
            TokenBlacklistService tokenBlacklistService,
            LoginAttemptService loginAttemptService,
            AuditEventLogService auditEventLogService,
            PasswordHistoryService passwordHistoryService,
            MfaService mfaService,
            WsTicketService wsTicketService,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.mfa.required-roles:}") List<String> mfaRequiredRoles) {
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authNotification = authNotification;
        this.userCredentialLifecycleService = userCredentialLifecycleService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginAttemptService = loginAttemptService;
        this.auditEventLogService = auditEventLogService;
        this.passwordHistoryService = passwordHistoryService;
        this.mfaService = mfaService;
        this.wsTicketService = wsTicketService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.mfaRequiredRoles = mfaRequiredRoles;
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
            @Parameter(description = "Login request payload", required = true) @Valid @RequestBody LoginRequest loginRequest) {
        long start = System.nanoTime();
        log.info("🔐 [LOGIN] Attempting login for user='{}' at {}", loginRequest.getUsername(),
                java.time.Instant.now());

        // ── Lockout check (T-12) ──
        if (loginAttemptService.isLocked(loginRequest.getUsername())) {
            long remainMs = loginAttemptService.remainingLockMs(loginRequest.getUsername());
            long remainMin = Math.max(1, (remainMs + 59_999) / 60_000);
            log.warn("🔐 [LOGIN] Account '{}' is locked", loginRequest.getUsername());
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.ACCOUNT_LOCKED)
                    .eventDescription("Login rejected — account locked")
                    .userName(loginRequest.getUsername())
                    .status(AuditStatus.FAILURE)
                    .build());
            return ResponseEntity.status(423)
                    .body(new MessageResponse("Account is temporarily locked. Try again in " + remainMin + " minute(s)."));
        }

        try {
            log.debug("🔐 [LOGIN] Authenticating via AuthenticationManager...");
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));
            log.debug("🔐 [LOGIN] AuthenticationManager succeeded; building tokens...");
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Collect ALL roles the user holds across assignments
            var allRoles = authentication.getAuthorities().stream()
                    .map(auth -> auth.getAuthority())
                    .distinct()
                    .toList();

            // ── Multi-role selection gate ──
            // If the user holds more than one role and has NOT yet chosen, ask them to pick.
            if (allRoles.size() > 1 && (loginRequest.getSelectedRole() == null || loginRequest.getSelectedRole().isBlank())) {
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                log.info("🔐 [LOGIN] Multi-role selection required for user='{}' roles={} in {}ms",
                        loginRequest.getUsername(), allRoles, elapsedMs);

                var body = JwtResponse.builder()
                        .roleSelectionRequired(true)
                        .availableRoles(allRoles)
                        .username(loginRequest.getUsername())
                        .build();
                return ResponseEntity.ok(body);
            }

            // Determine the effective role list for token generation
            List<String> effectiveRoles;
            if (loginRequest.getSelectedRole() != null && !loginRequest.getSelectedRole().isBlank()) {
                String selected = loginRequest.getSelectedRole().trim();
                // Validate the user actually holds this role
                if (allRoles.stream().noneMatch(r -> r.equalsIgnoreCase(selected))) {
                    log.warn("🔐 [LOGIN] User '{}' tried to select role '{}' but holds only {}",
                            loginRequest.getUsername(), selected, allRoles);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(new MessageResponse("You do not hold the role: " + selected));
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
            if (mfaService != null && isMfaRequiredForUser(effectiveRoles)) {
                boolean mfaEnabled = mfaService.isMfaEnabled(user.getId());
                String mfaToken = jwtTokenProvider.generateMfaToken(user.getUsername());

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
                // Store selected role in mfa token for later use after verification
                return ResponseEntity.ok(body);
            }

            String accessToken = jwtTokenProvider.generateAccessToken(descriptor);
            String refreshToken = jwtTokenProvider.generateRefreshToken(descriptor);
            log.debug("🔐 [LOGIN] Tokens generated (effectiveRoles={}); fetching profiles...", effectiveRoles);

            userCredentialLifecycleService.recordSuccessfulLogin(user.getId());
            loginAttemptService.resetAttempts(loginRequest.getUsername());

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
            return ResponseEntity.ok(body);

        } catch (BadCredentialsException ex) {
            loginAttemptService.recordFailure(loginRequest.getUsername());
            log.warn("🔐 [LOGIN] Bad credentials for user='{}'", loginRequest.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Invalid username or password."));
        } catch (DisabledException ex) {
            log.warn("🔐 [LOGIN] Disabled account user='{}'", loginRequest.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User account is disabled. Please verify your email."));
        } catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("🔐 [LOGIN] Unexpected failure user='{}' after {}ms : {} - {}", loginRequest.getUsername(),
                    elapsedMs, ex.getClass().getSimpleName(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Authentication failed."));
        } finally {
            resetSecurityContextToAnonymous();
        }
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
                    .body(new EmailVerificationResponseDTO("Email verification failed.", false));
        }
        var user = userOpt.get();
        if (!java.util.Objects.equals(dto.getToken(), user.getActivationToken())
                || user.getActivationTokenExpiresAt() == null
                || user.getActivationTokenExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new EmailVerificationResponseDTO("Email verification failed.", false));
        }

        // 1. Activate the user account
        user.setActive(true);
        user.setActivationToken(null);
        user.setActivationTokenExpiresAt(null);
        userRepository.save(user);

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
            @RequestParam("email") @Email @NotBlank String email) {
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

        String activationLink = String.format(
                "%s/verify?email=%s&token=%s",
                frontendBaseUrl, user.getEmail(), user.getActivationToken());
        try {
            authNotification.email().sendActivationEmail(user.getEmail(), activationLink);
            log.info("📧 Resent verification email to '{}'", user.getEmail());
        } catch (Exception e) {
            log.warn("⚠️ Failed to resend verification email to '{}': {}",
                    user.getEmail(), e.getMessage());
        }

        return ResponseEntity.ok(new MessageResponse(
                "If the email is registered, a new verification link has been sent."));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout current user", description = "Clears authentication context on the server side (stateless JWT requires client to discard tokens).")
    @ApiResponse(responseCode = "200", description = "Logout successful", content = @Content(schema = @Schema(implementation = MessageResponse.class)))
    public ResponseEntity<Object> logout(HttpServletRequest request) {
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
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(new MessageResponse("Logged out successfully."));
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
     * by {@link JwtTokenProvider#validateToken}.
     */
    @PostMapping("/token/refresh")
    @Operation(
        summary = "Refresh access token",
        description = "Exchange a valid refresh token for a new access token + rotated refresh token.")
    @ApiResponse(responseCode = "200", description = "Tokens refreshed")
    @ApiResponse(responseCode = "401", description = "Refresh token missing, invalid, or expired")
    public ResponseEntity<Object> refreshToken(
            @RequestBody(required = false) java.util.Map<String, String> body) {

        String refreshToken = body != null ? body.get("refreshToken") : null;
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("🔄 [REFRESH] Missing refreshToken in request body");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Refresh token is required."));
        }

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            log.warn("🔄 [REFRESH] Invalid or expired refresh token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Refresh token is invalid or has expired. Please log in again."));
        }

        // Replay detection: reject already-used refresh tokens
        String refreshJti = jwtTokenProvider.getJtiFromToken(refreshToken);
        if (refreshJti != null && tokenBlacklistService.isBlacklisted(refreshJti)) {
            log.warn("🔄 [REFRESH] Replay detected — refresh token jti={} already used", refreshJti);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Refresh token has already been used. Please log in again."));
        }

        String username = jwtTokenProvider.getUsernameFromJWT(refreshToken);
        var user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isActive()) {
            log.warn("🔄 [REFRESH] User '{}' not found or inactive", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User account not found or inactive."));
        }

        // Resolve current roles from tenant assignments (authoritative source)
        List<String> roles = assignmentRepository.findByUser(user).stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .map(a -> a.getRole().getCode())
                .distinct()
                .toList();

        var descriptor = new com.example.hms.security.TokenUserDescriptor(user.getId(), username, roles);
        String newAccessToken  = jwtTokenProvider.generateAccessToken(descriptor);

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
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(refreshAuth);

        // Blacklist the old refresh token to prevent replay
        if (refreshJti != null) {
            long oldRefreshExp = jwtTokenProvider.getExpiration(refreshToken).getTime();
            tokenBlacklistService.blacklist(refreshJti, oldRefreshExp);
            log.debug("🔄 [REFRESH] Blacklisted old refresh token jti={}", refreshJti);
        }

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
    }

    /**
     * Verify current user's password without issuing new tokens.
     * Used by the lock-screen to re-authenticate when the session is idle.
     * Requires a valid JWT (user is already logged in but screen is locked).
     */
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
                    .body(new MessageResponse("Invalid password."));
        } catch (DisabledException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Account is disabled."));
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
                    .body(new MessageResponse("Not authenticated."));
        }
        String username = authentication.getName();
        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User not found."));
        }
        var user = userOpt.get();

        // Verify the user knows their current credentials (prevents CSRF token theft)
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.currentPassword()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Current password is incorrect."));
        } finally {
            resetSecurityContextToAnonymous();
            // Restore the original authentication after verify sub-call
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        if (request.newPassword() == null || request.newPassword().length() < 8) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("New password must be at least 8 characters."));
        }
        if (request.newPassword().equals(request.currentPassword())) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("New password must differ from the current password."));
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
                    .body(new MessageResponse("New password must not match any of your last 5 passwords."));
        }

        // Update password, clear force-change flag, and record in history
        userService.changeOwnPassword(user.getId(), request.newPassword());

        log.info("🔑 [CHANGE-PWD] Password changed for user='{}'; forcePasswordChange cleared.", username);
        return ResponseEntity.ok(new MessageResponse("Password changed successfully."));
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
                    .body(new MessageResponse("Not authenticated."));
        }
        String currentUsername = authentication.getName();
        var userOpt = userRepository.findByUsername(currentUsername);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("User not found."));
        }
        var user = userOpt.get();

        String newUsername = request.newUsername() == null ? "" : request.newUsername().trim();
        if (newUsername.length() < 3 || newUsername.length() > 50) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Username must be between 3 and 50 characters."));
        }
        if (!newUsername.matches("^[a-zA-Z0-9._-]+$")) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Username may only contain letters, digits, dots, hyphens, and underscores."));
        }
        if (newUsername.equals(currentUsername)) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("New username must differ from the current username."));
        }

        try {
            userService.changeOwnUsername(user.getId(), newUsername);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse(ex.getMessage()));
        }

        log.info("🔑 [CHANGE-USR] Username changed for user='{}' -> '{}'; forceUsernameChange cleared.",
                currentUsername, newUsername);
        return ResponseEntity.ok(new MessageResponse("Username changed successfully."));
    }

    /** Request body for {@code POST /auth/me/change-username}. */
    public record ChangeUsernameRequest(@NotBlank String newUsername) {}

    @PostMapping("/request-reset")
    public ResponseEntity<Void> legacyRequestPasswordReset(
            @RequestParam("email") @Email @NotBlank String email,
            Locale locale,
            HttpServletRequest request) {
        String ip = clientIp(request);
        try {
            authNotification.passwordReset().requestReset(email, locale, ip);
        } catch (ResourceNotFoundException e) {
            log.debug("Password reset requested for non-existent email: {}", email);
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

    @PutMapping("/credentials/mfa")
    public ResponseEntity<List<UserMfaEnrollmentDTO>> updateMfaEnrollments(
            @Valid @RequestBody List<UserMfaEnrollmentRequestDTO> payload) {
        UUID userId = resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<UserMfaEnrollmentDTO> response = userCredentialLifecycleService.upsertMfaEnrollments(userId, payload);
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
    @PostMapping("/ws-ticket")
    public ResponseEntity<Map<String, String>> issueWsTicket(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }
        String username = authentication.getName();
        String ticket = wsTicketService.issue(username);
        return ResponseEntity.ok(Map.of("ticket", ticket));
    }

    /* ── Hospital assignment context ── */
    private record HospitalContext(UUID primaryHospitalId, String primaryHospitalName,
                                   List<UUID> hospitalIds) {}

    /**
     * Resolve hospital context from the user's active tenant role assignments.
     * Returns the primary hospital (first active assignment) and all permitted hospital IDs.
     */
    private boolean isMfaRequiredForUser(List<String> roles) {
        if (mfaRequiredRoles == null || mfaRequiredRoles.isEmpty()) {
            return false;
        }
        return roles.stream().anyMatch(mfaRequiredRoles::contains);
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
