package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.SessionBootstrapResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthBootstrapServiceImpl implements AuthBootstrapService {

    private static final String KEYCLOAK_AUTH_SOURCE = "keycloak";

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    @Override
    @Transactional
    public SessionBootstrapResponseDTO resolveCurrentSession(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByUsername", username));

        // Side-effect: track most-recent OIDC login timestamp
        if (KEYCLOAK_AUTH_SOURCE.equals(user.getAuthSource())) {
            user.setLastOidcLoginAt(OffsetDateTime.now(ZoneOffset.UTC));
            userRepository.save(user);
        }

        List<TenantRoleAssignment> assignments =
                tenantRoleAssignmentAccessor.findAssignmentsForUser(user.getId());

        List<String> roles = assignments.stream()
                .filter(TenantRoleAssignment::active)
                .map(TenantRoleAssignment::roleCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();

        boolean isSuperAdmin = roles.stream()
                .anyMatch(r -> r.equalsIgnoreCase("ROLE_SUPER_ADMIN"));
        boolean isHospitalAdmin = roles.stream()
                .anyMatch(r -> r.equalsIgnoreCase("ROLE_HOSPITAL_ADMIN"));

        List<UUID> permittedHospitalIds = assignments.stream()
                .filter(TenantRoleAssignment::active)
                .map(TenantRoleAssignment::hospitalId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        UUID primaryHospitalId = permittedHospitalIds.isEmpty() ? null : permittedHospitalIds.get(0);

        String primaryHospitalName = null;
        if (primaryHospitalId != null) {
            primaryHospitalName = hospitalRepository.findById(primaryHospitalId)
                    .map(h -> h.getName())
                    .orElse(null);
        }

        // Staff profile (optional)
        Staff staff = staffRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElse(null);

        UUID staffId = null;
        String staffRoleCode = null;
        UUID departmentId = null;
        String departmentName = null;

        if (staff != null) {
            staffId = staff.getId();
            // Derive staff role code from first active assignment for this staff's hospital
            if (staff.getHospital() != null) {
                UUID staffHospitalId = staff.getHospital().getId();
                staffRoleCode = assignments.stream()
                        .filter(TenantRoleAssignment::active)
                        .filter(a -> staffHospitalId.equals(a.hospitalId()))
                        .map(TenantRoleAssignment::roleCode)
                        .filter(code -> code != null && !code.isBlank())
                        .findFirst()
                        .orElse(null);
                // Use staff hospital as primary when no explicit primary from assignments
                if (primaryHospitalId == null) {
                    primaryHospitalId = staffHospitalId;
                    primaryHospitalName = staff.getHospital().getName();
                }
            }
            if (staff.getDepartment() != null) {
                departmentId = staff.getDepartment().getId();
                departmentName = staff.getDepartment().getName();
            }
        }

        // Patient profile (optional)
        Patient patient = patientRepository.findByUserId(user.getId()).orElse(null);
        UUID patientId = patient != null ? patient.getId() : null;

        Instant lastOidcLoginAt = user.getLastOidcLoginAt() != null
                ? user.getLastOidcLoginAt().toInstant()
                : null;

        log.debug("[BOOTSTRAP] Resolved session for username='{}' roles={} primaryHospital={}",
                username, roles, primaryHospitalId);

        return SessionBootstrapResponseDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .profileImageUrl(user.getProfileImageUrl())
                .authSource(user.getAuthSource())
                .roles(roles)
                .superAdmin(isSuperAdmin)
                .hospitalAdmin(isHospitalAdmin)
                .primaryHospitalId(primaryHospitalId)
                .primaryHospitalName(primaryHospitalName)
                .permittedHospitalIds(permittedHospitalIds)
                .staffId(staffId)
                .staffRoleCode(staffRoleCode)
                .departmentId(departmentId)
                .departmentName(departmentName)
                .patientId(patientId)
                .lastOidcLoginAt(lastOidcLoginAt)
                .build();
    }
}
