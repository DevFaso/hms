package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.MessageResponse;
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
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
        final Set<String> callerAuthorities = extractAuthorities(auth);

        final boolean callerHasElevatedRole = callerAuthorities.stream().anyMatch(authority ->
            "ROLE_SUPER_ADMIN".equals(authority) ||
                "ROLE_HOSPITAL_ADMIN".equals(authority) ||
                "ROLE_ADMIN".equals(authority));

        final boolean callerIsReceptionistOnly = callerAuthorities.contains("ROLE_RECEPTIONIST") && !callerHasElevatedRole;

        Set<String> effectiveRoles = resolveEffectiveRoles(request, callerIsReceptionistOnly);
        request.setRoleNames(effectiveRoles);

        final boolean isPatientOnly = effectiveRoles.size() == 1 &&
            (effectiveRoles.contains(ROLE_PATIENT) || effectiveRoles.contains("ROLE_PATIENT"));

        if (!callerIsReceptionistOnly && !isPatientOnly && request.getHospitalId() == null) {
            ResponseEntity<UserResponseDTO> badRequest = resolveHospitalFromName(request);
            if (badRequest != null) return badRequest;
        }

        log.info("[ADMIN REGISTER] normalized -> username={}, email={}, roles={}, hospitalId={}",
            request.getUsername(), request.getEmail(), request.getRoleNames(), request.getHospitalId());

        UserResponseDTO dto = userService.createUserWithRolesAndHospital(request);
        return ResponseEntity.created(URI.create("/users/" + dto.getId())).body(dto);
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
                log.warn("[ADMIN REGISTER] Hospital not found for name: {}", request.getHospitalName());
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
