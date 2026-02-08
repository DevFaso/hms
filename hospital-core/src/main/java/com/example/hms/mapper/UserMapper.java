package com.example.hms.mapper;

import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.RoleResponseDTO;
import com.example.hms.payload.dto.UserRequestDTO;
import com.example.hms.payload.dto.UserResponseDTO;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class UserMapper {

    /* ===================== Public API ===================== */

    /** Legacy: maps from user + userRoles only (no hospital context). */
    public UserResponseDTO toResponseDTO(User user) {
        if (user == null)
            return null;

        // roles from legacy userRoles
        Set<Role> roles = safeRolesFromUserRoles(user);
        Set<RoleResponseDTO> roleDtos = mapRolesToDTOs(roles);

        // profile derivation
        String profileType = deriveProfileType(user);
        String licenseNumber = user.getStaffProfile() != null ? user.getStaffProfile().getLicenseNumber() : null;

        // primary role by priority
        String roleName = pickPrimaryRole(roles, /* fallbackNames */ true);

        return UserResponseDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phoneNumber(user.getPhoneNumber())
                .profileImageUrl(user.getProfileImageUrl())
                .active(user.isActive())
                .forcePasswordChange(user.isForcePasswordChange())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(roleDtos != null ? roleDtos : Collections.emptySet())
                .profileType(profileType)
                .licenseNumber(licenseNumber)
                .roleName(roleName)
                .patientId(user.getPatientProfile() != null ? user.getPatientProfile().getId() : null)
                .staffId(user.getStaffProfile() != null ? user.getStaffProfile().getId() : null)
                .build();
    }

    /**
     * Preferred: maps from user + assignments when you have them (e.g., after
     * admin-register).
     * Uses assignments to compute active state and roles more accurately.
     */
    public UserResponseDTO toResponseDTO(User user, Set<UserRoleHospitalAssignment> assignments) {
        if (user == null)
            return null;

        // roles from assignments (distinct, ordered)
        Set<Role> rolesFromAssignments = safeRolesFromAssignments(assignments);
        Set<Role> roles = !rolesFromAssignments.isEmpty() ? rolesFromAssignments : safeRolesFromUserRoles(user);
        Set<RoleResponseDTO> roleDtos = mapRolesToDTOs(roles);

        // active: any active assignment? fallback to user.isActive()
        boolean active = (assignments != null && assignments.stream().anyMatch(UserRoleHospitalAssignment::getActive))
                || user.isActive();

        // profile derivation
        String profileType = deriveProfileType(user);
        String licenseNumber = user.getStaffProfile() != null ? user.getStaffProfile().getLicenseNumber() : null;

        // primary role by priority, prefer active & newest assignments first
        String roleName = pickPrimaryRoleWithAssignments(assignments, roles);

        return UserResponseDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phoneNumber(user.getPhoneNumber())
                .profileImageUrl(user.getProfileImageUrl())
                .active(active)
                .forcePasswordChange(user.isForcePasswordChange())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(roleDtos != null ? roleDtos : Collections.emptySet())
                .profileType(profileType)
                .licenseNumber(licenseNumber)
                .roleName(roleName)
                .patientId(user.getPatientProfile() != null ? user.getPatientProfile().getId() : null)
                .staffId(user.getStaffProfile() != null ? user.getStaffProfile().getId() : null)
                .build();
    }

    public User toEntity(UserRequestDTO dto, String encodedPasswordHash) {
        if (dto == null)
            return null;

        return User.builder()
                .username(trim(dto.getUsername()))
                .passwordHash(encodedPasswordHash)
                .email(normalizeEmail(dto.getEmail()))
                .firstName(trim(dto.getFirstName()))
                .lastName(trim(dto.getLastName()))
                .phoneNumber(trim(dto.getPhoneNumber()))
                .isActive(Boolean.TRUE.equals(dto.getActive()))
                .build();
    }

    /** Back-compat (prefer the overload with encoded password). */
    @Deprecated
    public User toEntity(UserRequestDTO dto) {
        if (dto == null)
            return null;

        return User.builder()
                .username(trim(dto.getUsername()))
                .passwordHash(dto.getPassword())
                .email(normalizeEmail(dto.getEmail()))
                .firstName(trim(dto.getFirstName()))
                .lastName(trim(dto.getLastName()))
                .phoneNumber(trim(dto.getPhoneNumber()))
                .isActive(Boolean.TRUE.equals(dto.getActive()))
                .build();
    }

    /** Partial update without forcing active flag unless provided. */
    public void updateEntityFromDTO(User user, UserRequestDTO dto,
            String encodedPasswordHashOrNull, Boolean activeOrNull) {
        if (dto == null || user == null)
            return;

        if (dto.getUsername() != null)
            user.setUsername(trim(dto.getUsername()));
        if (encodedPasswordHashOrNull != null)
            user.setPasswordHash(encodedPasswordHashOrNull);
        if (dto.getEmail() != null)
            user.setEmail(normalizeEmail(dto.getEmail()));
        if (dto.getFirstName() != null)
            user.setFirstName(trim(dto.getFirstName()));
        if (dto.getLastName() != null)
            user.setLastName(trim(dto.getLastName()));
        if (dto.getPhoneNumber() != null)
            user.setPhoneNumber(trim(dto.getPhoneNumber()));
        if (activeOrNull != null)
            user.setActive(activeOrNull);
    }

    public UserResponseDTO toDto(User user) {
        return toResponseDTO(user);
    }

    /* ===================== Internals ===================== */

    private Set<Role> safeRolesFromUserRoles(User user) {
        if (user == null || user.getUserRoles() == null)
            return Collections.emptySet();
        return user.getUserRoles().stream()
                .filter(Objects::nonNull)
                .map(UserRole::getRole)
                .filter(Objects::nonNull)
                // preserve insertion order for stability
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Role> safeRolesFromAssignments(Set<UserRoleHospitalAssignment> assignments) {
        if (assignments == null || assignments.isEmpty())
            return Collections.emptySet();
        return assignments.stream()
                .filter(Objects::nonNull)
                .map(UserRoleHospitalAssignment::getRole)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<RoleResponseDTO> mapRolesToDTOs(Set<Role> roles) {
        if (roles == null || roles.isEmpty())
            return Collections.emptySet();
        return roles.stream().map(role -> RoleResponseDTO.builder()
                .id(role.getId())
                .code(nonBlankOr(role.getCode(), role.getName()))
                .authority(nonBlankOr(role.getCode(), role.getName()))
                .name(nonBlankOr(role.getName(), role.getCode()))
                .description(role.getDescription())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String deriveProfileType(User user) {
        if (user == null)
            return null;
        boolean hasStaff = user.getStaffProfile() != null;
        boolean hasPatient = user.getPatientProfile() != null;
        if (hasStaff && hasPatient)
            return "STAFF"; // prefer STAFF if both exist (tweak if needed)
        if (hasStaff)
            return "STAFF";
        if (hasPatient)
            return "PATIENT";
        return null;
    }

    private String pickPrimaryRole(Set<Role> roles, boolean fallbackNames) {
        if (roles == null || roles.isEmpty())
            return null;

        // priority list
        List<String> priority = List.of(
                "ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN",
                "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_LAB_SCIENTIST",
                "ROLE_PHARMACIST", "ROLE_RECEPTIONIST", "ROLE_PATIENT");

        // collect codes/names
        Set<String> codes = roles.stream()
                .map(role -> nonBlankOr(role.getCode(), fallbackNames ? role.getName() : null))
                .filter(Objects::nonNull)
                .map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String p : priority) {
            if (codes.contains(p))
                return p;
        }
        return codes.stream().findFirst().orElse(null);
    }

    private String pickPrimaryRoleWithAssignments(Set<UserRoleHospitalAssignment> assignments,
            Set<Role> rolesFallback) {
        if (assignments != null && !assignments.isEmpty()) {
            // prefer active, then newest
            Optional<Role> preferred = assignments.stream()
                    .sorted(Comparator
                            .comparing(UserRoleHospitalAssignment::getActive, Comparator.nullsLast(Boolean::compareTo))
                            .reversed()
                            .thenComparing(UserRoleHospitalAssignment::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(UserRoleHospitalAssignment::getRole)
                    .filter(Objects::nonNull)
                    .findFirst();

            if (preferred.isPresent()) {
                Role r = preferred.get();
                return nonBlankOr(r.getCode(), r.getName());
            }
        }
        return pickPrimaryRole(rolesFallback, true);
    }

    private String normalizeEmail(String email) {
        String e = trim(email);
        return e != null ? e.toLowerCase(Locale.ROOT) : null;
    }

    private String trim(String s) {
        return (s == null) ? null : s.trim();
    }

    private static String nonBlankOr(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
