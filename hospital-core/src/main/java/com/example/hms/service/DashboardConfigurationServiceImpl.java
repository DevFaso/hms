package com.example.hms.service;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.dashboard.DashboardConfigResponseDTO;
import com.example.hms.payload.dto.dashboard.DashboardRoleConfigDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.permission.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardConfigurationServiceImpl implements DashboardConfigurationService {

    private static final String UNKNOWN_ROLE_CODE = "ROLE_UNKNOWN";
    private static final String UNKNOWN_ROLE_NAME = "Unknown Role";

    private static final Map<String, Integer> ROLE_PRIORITY = Map.ofEntries(
        Map.entry("ROLE_SUPER_ADMIN", 0),
        Map.entry("ROLE_HOSPITAL_ADMIN", 1),
        Map.entry("ROLE_DOCTOR", 2),
        Map.entry("ROLE_SURGEON", 3),
        Map.entry("ROLE_NURSE", 4),
        Map.entry("ROLE_MIDWIFE", 5),
        Map.entry("ROLE_PHARMACIST", 6),
        Map.entry("ROLE_RADIOLOGIST", 7),
        Map.entry("ROLE_ANESTHESIOLOGIST", 8),
        Map.entry("ROLE_RECEPTIONIST", 9),
        Map.entry("ROLE_BILLING_SPECIALIST", 10),
        Map.entry("ROLE_LAB_SCIENTIST", 11),
        Map.entry("ROLE_PHYSIOTHERAPIST", 12),
        Map.entry("ROLE_PATIENT", 13)
    );

    private final AuthService authService;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    @Override
    public DashboardConfigResponseDTO getDashboardForCurrentUser() {
        UUID userId = authService.getCurrentUserId();
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUser_IdAndActiveTrue(userId);

        List<UserRoleHospitalAssignment> sortedAssignments = assignments.stream()
            .sorted(
                Comparator
                    .comparing((UserRoleHospitalAssignment assignment) -> priorityForRole(normalizeRoleCode(assignment.getRole())))
                    .thenComparing(assignment -> normalizeRoleCode(assignment.getRole()))
                    .thenComparing(assignment -> hospitalName(assignment.getHospital()), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            )
            .toList();

        LinkedHashMap<String, DashboardRoleConfigDTO> perAssignmentConfig = new LinkedHashMap<>();
        LinkedHashSet<String> mergedPermissions = new LinkedHashSet<>();

        for (UserRoleHospitalAssignment assignment : sortedAssignments) {
            Role role = assignment.getRole();
            String roleCode = normalizeRoleCode(role);
            String key = roleCode + "|" + assignmentHospitalKey(assignment.getHospital());
            if (perAssignmentConfig.containsKey(key)) {
                continue;
            }

            List<String> permissions = PermissionCatalog.permissionsForRole(roleCode);
            mergedPermissions.addAll(permissions);

            perAssignmentConfig.put(
                key,
                new DashboardRoleConfigDTO(
                    roleCode,
                    resolveRoleName(role),
                    assignment.getHospital() != null ? assignment.getHospital().getId() : null,
                    assignment.getHospital() != null ? assignment.getHospital().getName() : null,
                    permissions
                )
            );
        }

        List<DashboardRoleConfigDTO> roleConfigs = new ArrayList<>(perAssignmentConfig.values());
        if (roleConfigs.isEmpty()) {
            List<String> fallbackPermissions = PermissionCatalog.permissionsForRole(UNKNOWN_ROLE_CODE);
            mergedPermissions.addAll(fallbackPermissions);
            roleConfigs = List.of(new DashboardRoleConfigDTO(
                UNKNOWN_ROLE_CODE,
                UNKNOWN_ROLE_NAME,
                null,
                null,
                fallbackPermissions
            ));
        }

        String primaryRoleCode = roleConfigs.get(0).roleCode();
        List<String> mergedPermissionsList = new ArrayList<>(mergedPermissions);

        return new DashboardConfigResponseDTO(userId, primaryRoleCode, roleConfigs, mergedPermissionsList);
    }

    private static String normalizeRoleCode(Role role) {
        if (role == null) {
            return UNKNOWN_ROLE_CODE;
        }
        String code = role.getCode();
        if (StringUtils.hasText(code)) {
            return code.trim().toUpperCase(Locale.ROOT);
        }
        String name = role.getName();
        if (StringUtils.hasText(name)) {
            String upper = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
        }
        return UNKNOWN_ROLE_CODE;
    }

    private static String resolveRoleName(Role role) {
        if (role == null || !StringUtils.hasText(role.getName())) {
            return UNKNOWN_ROLE_NAME;
        }
        return role.getName();
    }

    private static String assignmentHospitalKey(Hospital hospital) {
        if (hospital == null || hospital.getId() == null) {
            return "GLOBAL";
        }
        return hospital.getId().toString();
    }

    private static String hospitalName(Hospital hospital) {
        if (hospital == null) {
            return null;
        }
        return hospital.getName();
    }

    private static int priorityForRole(String roleCode) {
        return ROLE_PRIORITY.getOrDefault(roleCode, ROLE_PRIORITY.size() + 1);
    }
}
