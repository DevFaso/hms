package com.example.hms.controller;

import com.example.hms.config.SecurityConstants;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.UpdateUserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.payload.dto.UserSummaryDTO;
import com.example.hms.service.UserService;
import com.example.hms.utility.RoleValidator;
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
    private final com.example.hms.service.PatientAccountRegistrationService patientAccountRegistrationService;
    private final com.example.hms.repository.HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;
    private final org.springframework.context.MessageSource messageSource;

    @WriteAudited(skip = true, reason = "service emits USER_CREATE / USER_UPDATE / USER_DELETE")
    @Operation(
        summary = "Admin: Create a user with specific roles and hospital assignment",
        description = "SUPER/HOSPITAL_ADMIN can register any role. RECEPTIONIST can only register PATIENT; hospital is resolved from JWT."
    )
    @PostMapping("/admin-register")
    @PreAuthorize("hasAnyAuthority(" + SecurityConstants.ADMIN_REGISTER_AUTHORITIES + ")")
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

        // QA D626 (regression of D329): D329 refused PATIENT only NEXT TO a
        // staff role, so a staff onboarding that ticked the patient role alone
        // still answered 201 with an account and no patient record. Only the
        // patient form (which says so) or a receptionist (whose roles are
        // forced to PATIENT above) registers a patient here.
        if (!callerIsReceptionistOnly
            && effectiveRoles.stream().anyMatch(r -> ROLE_PATIENT.equalsIgnoreCase(r.replaceFirst("(?i)^ROLE_", "")))
            && !Boolean.TRUE.equals(request.getPatientRegistration())) {
            throw new com.example.hms.exception.BusinessException("users.roles.patientFromPatientForm");
        }
        // QA D626 (verification): the flag alone proves nothing, the client sets
        // it. A patient registration carries the patient record and both are
        // written in one transaction, so no PATIENT account is left without one.
        final boolean patientRegistration = Boolean.TRUE.equals(request.getPatientRegistration());
        if (patientRegistration && request.getPatient() == null) {
            throw new com.example.hms.exception.BusinessException("users.patientRegistration.patientRequired");
        }

        // SUPER_ADMIN is a global (platform-level) role — it has no hospital context.
        // Skip the hospital-presence check so the request reaches the service, which
        // correctly returns null for the hospitalId when registering a SUPER_ADMIN.
        final boolean isGlobalRole = effectiveRoles.stream().anyMatch(r ->
            "SUPER_ADMIN".equalsIgnoreCase(r) || SUPER_ADMIN_AUTHORITY.equalsIgnoreCase(r));

        if (!callerIsReceptionistOnly && !isPatientOnly && !isGlobalRole && request.getHospitalId() == null) {
            resolveHospitalFromName(request);
        }

        // Delivery outcomes are recorded by the send paths — including the
        // AFTER_COMMIT assignment listener, which runs on this thread after
        // the service transaction commits and before we resume — so the
        // registrar learns HERE when the activation message went nowhere,
        // instead of a green 201 over a silent WARN log.
        com.example.hms.utility.ActivationDeliveryTracker.open();
        try {
            UserResponseDTO dto = patientRegistration
                ? patientAccountRegistrationService.registerPatient(request,
                    org.springframework.context.i18n.LocaleContextHolder.getLocale())
                : userService.createUserWithRolesAndHospital(request);
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

    /**
     * A staff registration with no hospital id: by name when one is given,
     * otherwise the hospital the caller acts at (a hospital admin onboards
     * into their own hospital, D201). With neither, a translated 400 body
     * instead of an empty one.
     */
    private void resolveHospitalFromName(AdminSignupRequest request) {
        if (request.getHospitalName() != null && !request.getHospitalName().isBlank()) {
            // By NAME a hospital only: a provider facility is named by id (the
            // path of its PROVIDER_ADMIN), and by name it answers as an unknown
            // hospital (provider plan AC-11).
            var hospital = hospitalRepository.findClinicalByName(request.getHospitalName())
                .orElse(null);
            if (hospital == null) {
                log.warn("[ADMIN REGISTER] Hospital not found for provided hospital name.");
                throw new com.example.hms.exception.BusinessException("user.adminRegister.hospitalNotFound");
            }
            request.setHospitalId(hospital.getId());
            return;
        }
        UUID acting = com.example.hms.security.tenant.ActingScopeResolver.pinnedHospitalIdOrNull();
        if (acting != null) {
            request.setHospitalId(acting);
            return;
        }
        log.warn("[ADMIN REGISTER] Missing hospital for non-patient staff/admin registration.");
        throw new com.example.hms.exception.BusinessException("user.adminRegister.hospitalRequired");
    }

    // Who may read, edit, delete and restore which account, and who may use the
    // directory, is decided in the service (UserAccountAccess), so no other
    // entry point over UserService can skip it. A refused id answers 404, the
    // same as a missing one.
    @Operation(summary = "Get user by ID",
        description = "Your own account, or one you administer (super-admin; hospital admin of a "
            + "hospital the account is assigned to). Anything else answers 404.")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDTO> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @Operation(summary = "Get all users with pagination (summary view)",
        description = "Administrators only (QA D790): a caller with no active HOSPITAL_ADMIN or ADMIN "
            + "assignment gets 403. A super-admin sees every account; an administrator "
            + "sees the live accounts holding an assignment (any role, active or not) at a hospital "
            + "they administer. includeDeleted=true also returns soft-deleted "
            + "accounts; onlyDeleted=true returns only them (the restore worklist). Both are "
            + "honoured only for SUPER_ADMIN; everyone else silently gets the live-only view.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority(" + SecurityConstants.USER_DIRECTORY_AUTHORITIES + ")")
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
     * Deleted-account visibility is SUPER_ADMIN-only. A soft delete removes
     * the account's assignments, so a deleted account belongs to no hospital
     * and the hospital-scoped directory has nothing to match it on: honouring
     * this for a HOSPITAL_ADMIN would hand them every tenant's deleted
     * identities. The scoped service query has no deleted view at all.
     */
    private boolean canSeeDeleted() {
        // The same super-admin signal the rest of the /users rules use
        // (UserAccountAccess), so the two cannot disagree.
        return roleValidator.isSuperAdminFromJwtClaim();
    }

    @WriteAudited(skip = true, reason = "service emits USER_CREATE / USER_UPDATE / USER_DELETE")
    @Operation(summary = "Update user by ID (partial update — only send fields you want to change)",
        description = "Your own account: names and phone only. The password and username have "
            + "their own endpoints (POST /auth/me/change-password, /auth/me/change-username); "
            + "your email goes through POST /auth/me/change-email and its code (400 on a self "
            + "change here), and nobody "
            + "switches their own account on or off. Another account: super-admin, or a hospital admin when "
            + "every one of its assignments is at a hospital they administer and it is not a "
            + "super-admin. Anything else answers 404.")
    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDTO> updateUser(@PathVariable UUID id,
                                                      @Valid @RequestBody UpdateUserRequestDTO dto) {
        return ResponseEntity.ok(userService.updateUser(id, dto));
    }

    @WriteAudited(skip = true, reason = "service emits USER_CREATE / USER_UPDATE / USER_DELETE")
    @Operation(summary = "Delete user by ID (Soft Delete)",
        description = "Administrators of the account, as for update. A registrar (the roles "
            + "admin-register admits) may also discard the unclaimed patient account its own "
            + "failed patient registration just created. Anything else answers 404.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority(" + SecurityConstants.USER_REGISTRAR_AUTHORITIES + ")")
    public ResponseEntity<MessageResponse> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        // QA D911: in the reader's language, like every other answer here.
        return ResponseEntity.ok(new MessageResponse(messageSource.getMessage(
            "user.deleted", null, org.springframework.context.i18n.LocaleContextHolder.getLocale())));
    }

    @WriteAudited(skip = true, reason = "service emits USER_RESTORE")
    @Operation(summary = "Restore a soft-deleted user")
    @PatchMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN')")
    public ResponseEntity<Void> restoreUser(@PathVariable UUID id) {
        userService.restoreUser(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Colleagues the caller may start a chat with",
        description = "QA D790: the chat's new-conversation picker. Any caller holding an active "
            + "non-patient assignment; the active, live staff accounts at the caller's hospitals, "
            + "with id, name and role only (no username, e-mail or status flags). Patients get 403 "
            + "and use their own care-team pickers.")
    @GetMapping("/chat-recipients")
    public ResponseEntity<java.util.List<com.example.hms.payload.dto.ChatRecipientDTO>> getChatRecipients(
            @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(userService.listChatRecipients(size));
    }

    @Operation(summary = "Search users by name, role, or email with pagination (summary view)",
        description = "Administrators only (QA D790), scoped exactly as GET /users is.")
    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(" + SecurityConstants.USER_DIRECTORY_AUTHORITIES + ")")
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
