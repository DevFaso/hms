package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class UserRoleHospitalAssignmentMapper {

    /* ---------- Response ---------- */

    @SuppressWarnings("java:S3776")
    public UserRoleHospitalAssignmentResponseDTO toResponseDTO(UserRoleHospitalAssignment a) {
        if (a == null) return null;

        final User user = a.getUser();
        final Hospital h = a.getHospital();
        final Role r = a.getRole();
        final User reg = a.getRegisteredBy();

        return UserRoleHospitalAssignmentResponseDTO.builder()
            .id(a.getId())
            .assignmentCode(a.getAssignmentCode())

            // user
            .userId(user != null ? user.getId() : null)
            .userName(joinName(
                user != null ? user.getFirstName() : null,
                user != null ? user.getLastName() : null))
            .userEmail(user != null ? user.getEmail() : null)

            // hospital (optional)
            .hospitalId(h != null ? h.getId() : null)
            .hospitalName(h != null ? h.getName() : null)
            .hospitalCode(h != null ? h.getCode() : null)
            .hospitalAddress(h != null ? h.getAddress() : null)
            .hospitalPhone(h != null ? h.getPhoneNumber() : null)
            .hospitalLicenseNumber(h != null ? h.getLicenseNumber() : null)

            // role
            .roleId(r != null ? r.getId() : null)
            .roleName(r != null ? r.getName() : null)
            .roleCode(r != null ? r.getCode() : null)
            .code(r != null ? r.getCode() : null)

            .startDate(a.getStartDate())

            .confirmationSentAt(a.getConfirmationSentAt())
            .confirmationVerifiedAt(a.getConfirmationVerifiedAt())
            .confirmationVerified(a.getConfirmationVerifiedAt() != null)

            // registered by (optional)
            .registeredByUserId(reg != null ? reg.getId() : null)
            .registeredByUserName(joinName(
                reg != null ? reg.getFirstName() : null,
                reg != null ? reg.getLastName() : null))
            .registeredByUserPhone(reg != null ? reg.getPhoneNumber() : null)

            .active(Boolean.TRUE.equals(a.getActive()))
            .assignedAt(a.getAssignedAt())
            .createdAt(a.getCreatedAt())
            .updatedAt(a.getUpdatedAt())
            .build();
    }

    /* ---------- Create ---------- */

    /**
     * Build a new assignment. All related entities must be provided by the service layer.
     * If assignmentCode is null, assume the service will generate it.
     */
    public UserRoleHospitalAssignment toEntity(
        UserRoleHospitalAssignmentRequestDTO dto,
        User user,
        Hospital hospital,
        Role role
    ) {
    final Boolean isActive = dto.getActive() != null ? dto.getActive() : Boolean.TRUE;

    return UserRoleHospitalAssignment.builder()
            .assignmentCode(dto.getAssignmentCode())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(dto.getStartDate())
            .active(isActive)
            .build(); // <-- this is required!
    }

    /* ---------- Update (partial) ---------- */

    /**
     * Partially update fields. Callers control intent via arguments:
     * - To change hospital/role: resolve and pass the entity (possibly null to clear).
     * - If you pass null AND the DTO contained no corresponding ID, the field is left unchanged.
     */
    public void updateEntity(
        UserRoleHospitalAssignment target,
        UserRoleHospitalAssignmentRequestDTO dto,
        Hospital hospital,
        Role role,
        User registeredBy
    ) {
        if (dto.getAssignmentCode() != null) {
            target.setAssignmentCode(dto.getAssignmentCode());
        }
        if (dto.getStartDate() != null) {
            target.setStartDate(dto.getStartDate());
        }
        if (dto.getActive() != null) {
            target.setActive(dto.getActive());
        }

        // Hospital: update if caller resolved a value OR DTO referred to hospitalId
        if (hospital != null || dto.getHospitalId() != null) {
            target.setHospital(hospital); // may be null to explicitly clear (global role)
        }

        // Role: update if caller resolved a value OR DTO referred to roleId/roleName
        if (role != null || dto.getRoleId() != null || (dto.getRoleName() != null && !dto.getRoleName().isBlank())) {
            target.setRole(role);
        }

        // registeredBy is metadata; set only when explicitly provided
        if (dto.getRegisteredByUserId() != null) {
            target.setRegisteredBy(registeredBy);
        }
    }

    /* ---------- Helpers ---------- */

    private String joinName(String first, String last) {
        final String f = first == null ? "" : first.trim();
        final String l = last == null ? "" : last.trim();
        final String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}

