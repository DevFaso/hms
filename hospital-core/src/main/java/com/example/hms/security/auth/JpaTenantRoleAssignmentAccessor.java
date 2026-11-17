package com.example.hms.security.auth;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.Organization;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static java.util.Collections.emptyList;

/**
 * JPA-backed implementation that converts {@link UserRoleHospitalAssignment} entities into
 * lightweight {@link TenantRoleAssignment} views for JWT scope computations.
 */
@Component
@RequiredArgsConstructor
public class JpaTenantRoleAssignmentAccessor implements TenantRoleAssignmentAccessor {

    private final UserRoleHospitalAssignmentRepository repository;

    @Override
    public List<TenantRoleAssignment> findAssignmentsForUser(UUID userId) {
        if (userId == null) {
            return emptyList();
        }

        return repository.findAllDetailedByUserId(userId).stream()
            .map(this::toView)
            .toList();
    }

    private TenantRoleAssignment toView(UserRoleHospitalAssignment assignment) {
        UUID hospitalId = null;
        UUID organizationId = null;
        FacilityType facilityType = null;
        if (assignment.getHospital() != null) {
            hospitalId = assignment.getHospital().getId();
            // The raw type (V180: NOT NULL, default HOSPITAL): a row without one is
            // refused by TenantRoleAssignment instead of read as "not a provider".
            facilityType = assignment.getHospital().getFacilityType();
            Organization org = assignment.getHospital().getOrganization();
            if (org != null) {
                organizationId = org.getId();
            }
        }

        String roleCode = assignment.getRole() != null ? assignment.getRole().getCode() : null;
        String roleName = assignment.getRole() != null ? assignment.getRole().getName() : null;

        return new TenantRoleAssignment(
            hospitalId,
            organizationId,
            normalize(roleCode),
            normalize(roleName),
            Boolean.TRUE.equals(assignment.getActive()),
            facilityType
        );
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
