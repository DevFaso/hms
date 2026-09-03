package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.UpdateUserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserSummaryDTO;
import com.example.hms.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "User API", description = "Handles User CRUD operations, admin-controlled registration, and search")
public class UserController {
    private static final String ROLE_PATIENT = "PATIENT";
    private static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final com.example.hms.repository.HospitalRepository hospitalRepository;

    @Operation(
        summary = "Admin: Create a user with specific roles and hospital assignment",
        description = "SUPER/HOSPITAL_ADMIN can register any role. RECEPTIONIST can only register PATIENT; hospital is resolved from JWT."
    )
    @PostMapping("/admin-register")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST','ROLE_DOCTOR','ROLE_NURSE','ROLE_MIDWIFE')")
    public ResponseEntity<UserResponseDTO> adminRegister(
        @Valid @RequestBody AdminSignupRequest request,
        Authentication auth // inject instead of pulling from SecurityContextHolder
    ) {
        final Set<String> callerAuthorities = extractAuthorities(auth);

        final boolean callerHasElevatedRole = callerAuthorities.stream().anyMatch(authority ->
            SUPER_ADMIN_AUTHORITY.equals(authority) ||
                "ROLE_HOSPITAL_ADMIN".equals(authority) ||
                "ROLE_ADMIN".equals(authority));

        final boolean callerIsReceptionistOnly = callerAuthorities.contains("ROLE_RECEPTIONIST") && !callerHasElevatedRole;

        Set<String> effectiveRoles = resolveEffectiveRoles(request, callerIsReceptionistOnly);
        request.setRoleNames(effectiveRoles);

        final boolean isPatientOnly = effectiveRoles.size() == 1 &&
            (effectiveRoles.contains(ROLE_PATIENT) || effectiveRoles.contains("ROLE_PATIENT"));

        // SUPER_ADMIN is a global (platform-level) role — it has no hospital context.
        // Skip the hospital-presence check so the request reaches the service, which
        // correctly returns null for the hospitalId when registering a SUPER_ADMIN.
        final boolean isGlobalRole = effectiveRoles.stream().anyMatch(r ->
            "SUPER_ADMIN".equalsIgnoreCase(r) || SUPER_ADMIN_AUTHORITY.equalsIgnoreCase(r));

        if (!callerIsReceptionistOnly && !isPatientOnly && !isGlobalRole && request.getHospitalId() == null) {
            ResponseEntity<UserResponseDTO> badRequest = resolveHospitalFromName(request);
            if (badRequest != null) return badRequest;
        }

        // Delivery outcomes are recorded by the send paths — including the
        // AFTER_COMMIT assignment listener, which runs on this thread after
        // the service transaction commits and before we resume — so the
        // registrar learns HERE when the activation message went nowhere,
        // instead of a green 201 over a silent WARN log.
        com.example.hms.utility.ActivationDeliveryTracker.open();
        try {
            UserResponseDTO dto = userService.createUserWithRolesAndHospital(request);
            dto.setActivationDelivery(com.example.hms.utility.ActivationDeliveryTracker.close());
            return ResponseEntity.created(URI.create("/users/" + dto.getId())).body(dto);
        } finally {
            com.example.hms.utility.ActivationDeliveryTracker.close();
        }
    }

    private Set<String> extractAuthorities(Authentication auth) {
        return Optional.ofNullable(auth)
            .map(Authentication::getAuthorities)
            .stream()
            .flatMap(Collection::stream)
            .map(grantedAuthority -> grantedAuthority != null ? grantedAuthority.getAuthority() : null)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private Set<String> resolveEffectiveRoles(AdminSignupRequest request, boolean callerIsReceptionistOnly) {
        Set<String> normalizedIncoming = Optional.ofNullable(request.getRoleNames())
            .orElseGet(() -> new LinkedHashSet<>(Set.of(ROLE_PATIENT)))
            .stream()
            .filter(Objects::nonNull)
            .map(r -> r.trim().toUpperCase(Locale.ROOT))
            .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        return callerIsReceptionistOnly
            ? Set.of(ROLE_PATIENT)
            : normalizedIncoming.stream()
                .map(r -> r.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ResponseEntity<UserResponseDTO> resolveHospitalFromName(AdminSignupRequest request) {
        if (request.getHospitalName() != null && !request.getHospitalName().isBlank()) {
            var hospital = hospitalRepository.findByName(request.getHospitalName()).orElse(null);
            if (hospital == null) {
                log.warn("[ADMIN REGISTER] Hospital not found for provided hospital name.");
                return ResponseEntity.badRequest().build();
            }
            request.setHospitalId(hospital.getId());
            return null;
        }
        log.warn("[ADMIN REGISTER] Missing hospital for non-patient staff/admin registration.");
        return ResponseEntity.badRequest().build();
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDTO> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @Operation(summary = "Get all users with pagination (summary view)",
        description = "includeDeleted=true also returns soft-deleted accounts; onlyDeleted=true "
            + "returns only them (the restore worklist). Both are honoured only for SUPER_ADMIN "
            + "- the user directory is global, so surfacing deleted identities to a "
            + "hospital-scoped admin would let one tenant enumerate another tenant's account "
            + "history. Everyone else silently gets the live-only view.")
    @GetMapping
    public ResponseEntity<Page<UserSummaryDTO>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean onlyDeleted) {
        boolean allowed = canSeeDeleted();
        return ResponseEntity.ok(userService.getAllUsers(
            page, size, includeDeleted && allowed, onlyDeleted && allowed));
    }

    /**
     * Deleted-account visibility is SUPER_ADMIN-only. The user directory is
     * global (not hospital-scoped), so honouring this for HOSPITAL_ADMIN
     * would let one tenant's admin enumerate another tenant's deleted
     * identities; scoping the directory itself is the larger pre-existing
     * question, and the deleted view must not widen it.
     */
    private boolean canSeeDeleted() {
        // From the SecurityContext, not a method-injected Authentication: the
        // latter rides request.getUserPrincipal(), which is only populated by
        // the security filter chain and is null in filterless slices.
        Set<String> authorities = extractAuthorities(
            org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication());
        return authorities.contains(SUPER_ADMIN_AUTHORITY);
    }

    @Operation(summary = "Update user by ID (partial update — only send fields you want to change)")
    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDTO> updateUser(@PathVariable UUID id,
                                                      @Valid @RequestBody UpdateUserRequestDTO dto) {
        return ResponseEntity.ok(userService.updateUser(id, dto));
    }

    @Operation(summary = "Delete user by ID (Soft Delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<MessageResponse> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(new MessageResponse("User deleted successfully."));
    }

    @Operation(summary = "Restore a soft-deleted user")
    @PatchMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<Void> restoreUser(@PathVariable UUID id) {
        userService.restoreUser(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Search users by name, role, or email with pagination (summary view)")
    @GetMapping("/search")
    public ResponseEntity<Page<UserSummaryDTO>> searchUsers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean onlyDeleted) {
        boolean allowed = canSeeDeleted();
        return ResponseEntity.ok(userService.searchUsers(
            name, role, email, page, size, includeDeleted && allowed, onlyDeleted && allowed));
    }
}
