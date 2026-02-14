package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
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
import com.example.hms.repository.UserRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.service.PasswordResetService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.example.hms.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
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
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
@Slf4j
public class AuthController {

    private final UserRepository userRepository;

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordResetService passwordResetService;

    private final UserService userService;
    private final UserCredentialLifecycleService userCredentialLifecycleService;

    public AuthController(UserRepository userRepository,
            UserService userService,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            PasswordResetService passwordResetService,
            UserCredentialLifecycleService userCredentialLifecycleService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordResetService = passwordResetService;
        this.userCredentialLifecycleService = userCredentialLifecycleService;
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

        try {
            log.debug("🔐 [LOGIN] Authenticating via AuthenticationManager...");
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));
            log.debug("🔐 [LOGIN] AuthenticationManager succeeded; building tokens...");
            SecurityContextHolder.getContext().setAuthentication(authentication);

            String accessToken = jwtTokenProvider.generateAccessToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);
            log.debug("🔐 [LOGIN] Tokens generated; fetching user entity...");

            var user = userRepository.findByUsername(loginRequest.getUsername())
                    .orElseThrow(() -> new RuntimeException("Authenticated user not found"));

            userCredentialLifecycleService.recordSuccessfulLogin(user.getId());

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

            var roles = jwtTokenProvider.getRolesFromToken(accessToken);
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
                    .phoneNumber(user.getPhoneNumber())
                    .dateOfBirth(patient != null ? patient.getDateOfBirth() : null)
                    .gender(patient != null ? patient.getGender() : null)
                    .roles(roles)
                    .profileType(profileType)
                    .licenseNumber(staff != null ? staff.getLicenseNumber() : null)
                    .patientId(patient != null ? patient.getId() : null)
                    .staffId(staff != null ? staff.getId() : null)
                    .roleName(preferredRole)
                    .active(user.isActive())
                    .profilePictureUrl(user.getProfileImageUrl())
                    .build();

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("🔐 [LOGIN] Success user='{}' roles={} in {}ms", loginRequest.getUsername(), roles, elapsedMs);
            return ResponseEntity.ok(body);

        } catch (BadCredentialsException ex) {
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
        user.setActive(true);
        user.setActivationToken(null);
        user.setActivationTokenExpiresAt(null);
        userRepository.save(user);
        return ResponseEntity.ok(new EmailVerificationResponseDTO("Email verified successfully.", true));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout current user", description = "Clears authentication context on the server side (stateless JWT requires client to discard tokens).")
    @ApiResponse(responseCode = "200", description = "Logout successful", content = @Content(schema = @Schema(implementation = MessageResponse.class)))
    public ResponseEntity<Object> logout() {
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(new MessageResponse("Logged out successfully."));
    }

    @PostMapping("/request-reset")
    public ResponseEntity<Void> legacyRequestPasswordReset(
            @RequestParam("email") @Email @NotBlank String email,
            Locale locale,
            HttpServletRequest request) {
        String ip = clientIp(request);
        try {
            passwordResetService.requestReset(email, locale, ip);
        } catch (ResourceNotFoundException e) {
            log.debug("Password reset requested for non-existent email: {}", email);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> legacyConfirmPasswordReset(@Valid @RequestBody PasswordResetConfirmDTO dto) {
        try {
            passwordResetService.confirmReset(dto.getToken(), dto.getNewPassword());
        } catch (ResourceNotFoundException | IllegalStateException e) {
            log.debug("Password reset confirm failed: {}", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    // Temporary diagnostic: validate provided Authorization bearer token
    @GetMapping("/token/echo")
    public ResponseEntity<Object> echoToken(@RequestHeader(value = "Authorization", required = false) String authz) {
        if (authz == null || !authz.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(new MessageResponse("Missing or invalid Authorization header"));
        }
        String token = authz.substring("Bearer ".length());
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
}
