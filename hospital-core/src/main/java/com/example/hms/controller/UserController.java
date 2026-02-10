package com.example.hms.controller;

import com.example.hms.payload.dto.*;
import com.example.hms.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "User API", description = "Handles User CRUD operations, admin-controlled registration, and search")
public class UserController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final com.example.hms.repository.HospitalRepository hospitalRepository;

    @Operation(
        summary = "Admin: Create a user with specific roles and hospital assignment",
        description = "SUPER/HOSPITAL_ADMIN can register any role. RECEPTIONIST can only register PATIENT; hospital is resolved from JWT."
    )
    @PostMapping("/admin-register")
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_HOSPITAL_ADMIN','ROLE_RECEPTIONIST')")
    public ResponseEntity<UserResponseDTO> adminRegister(
        @Valid @RequestBody AdminSignupRequest request,
        Authentication auth // inject instead of pulling from SecurityContextHolder
    ) {
        final Set<String> callerAuthorities = Optional.ofNullable(auth)
            .map(Authentication::getAuthorities)
            .stream()
            .flatMap(Collection::stream)
            .map(grantedAuthority -> grantedAuthority != null ? grantedAuthority.getAuthority() : null)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        final boolean callerHasElevatedRole = callerAuthorities.stream().anyMatch(authority ->
            "ROLE_SUPER_ADMIN".equals(authority) ||
                "ROLE_HOSPITAL_ADMIN".equals(authority) ||
                "ROLE_ADMIN".equals(authority));

        final boolean callerIsReceptionistOnly = callerAuthorities.contains("ROLE_RECEPTIONIST") && !callerHasElevatedRole;

        // Normalize incoming roles (don’t depend on client prefixing)
        final Set<String> normalizedIncoming = Optional.ofNullable(request.getRoleNames())
            .orElseGet(() -> new LinkedHashSet<>(Set.of("PATIENT")))
            .stream()
            .filter(Objects::nonNull)
            .map(r -> r.trim().toUpperCase(Locale.ROOT))
            .map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        // Receptionist = PATIENT-only, but DO NOT clear hospitalId/name.
        final Set<String> effectiveRoles = callerIsReceptionistOnly
            ? Set.of("PATIENT") // service will re-normalize to ROLE_PATIENT
            : normalizedIncoming.stream()
                .map(r -> r.replaceFirst("^ROLE_", "")) // store back without prefix, like your current API
                .collect(Collectors.toCollection(LinkedHashSet::new));

        request.setRoleNames(effectiveRoles);

        // For non-receptionist staff/admin: if it’s not patient-only, make sure hospital is resolvable here.
        final boolean isPatientOnly = effectiveRoles.size() == 1 &&
            (effectiveRoles.contains("PATIENT") || effectiveRoles.contains("ROLE_PATIENT"));

    if (!callerIsReceptionistOnly && !isPatientOnly && request.getHospitalId() == null) {
            if (request.getHospitalName() != null && !request.getHospitalName().isBlank()) {
                var hospital = hospitalRepository.findByName(request.getHospitalName()).orElse(null);
                if (hospital == null) {
                    log.warn("[ADMIN REGISTER] Hospital not found for name: {}", request.getHospitalName());
                    return ResponseEntity.badRequest().build();
                }
                request.setHospitalId(hospital.getId());
            } else {
                log.warn("[ADMIN REGISTER] Missing hospital for non-patient staff/admin registration.");
                return ResponseEntity.badRequest().build();
            }
        }

        log.info("[ADMIN REGISTER] normalized -> username={}, email={}, roles={}, hospitalId={}",
            request.getUsername(), request.getEmail(), request.getRoleNames(), request.getHospitalId());

        UserResponseDTO dto = userService.createUserWithRolesAndHospital(request);
        return ResponseEntity.created(URI.create("/users/" + dto.getId())).body(dto);
    }

    @Operation(summary = "Get user by ID")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDTO> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @Operation(summary = "Get all users with pagination")
    @GetMapping
    public ResponseEntity<Page<UserResponseDTO>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(userService.getAllUsers(page, size));
    }

    @Operation(summary = "Update user by ID")
    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDTO> updateUser(@PathVariable UUID id,
                                                      @Valid @RequestBody UserRequestDTO userRequestDTO) {
        return ResponseEntity.ok(userService.updateUser(id, userRequestDTO));
    }

    @Operation(summary = "Delete user by ID (Soft Delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<MessageResponse> deleteUser(@PathVariable UUID id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(new MessageResponse("User deleted successfully."));
    }

    @Operation(summary = "Search users by name, role, or email with pagination")
    @GetMapping("/search")
    public ResponseEntity<Page<UserResponseDTO>> searchUsers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(userService.searchUsers(name, role, email, page, size));
    }
}
