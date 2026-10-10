package com.example.hms.service.provider;

import com.example.hms.model.Hospital;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.provider.RoleFacilityCompatibility;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which provider facility a {@code /provider/**} request acts at, and as
 * whom (provider plan §3.1, §6.5): the role is checked AT the facility, from
 * the caller's live active assignments, never from {@code hasAuthority} alone
 * (§3.3, T5).
 *
 * <ul>
 *   <li>The request is pinned (its only facility, or {@code X-Hospital-Id}):
 *       that facility, when it is a provider where the caller holds an
 *       active staff (non-PATIENT) assignment.</li>
 *   <li>The request has several facilities and named none (a pharmacist who
 *       is also a patient at a hospital): the caller's one provider facility,
 *       when there is exactly one.</li>
 *   <li>Anything else (a hospital user, a super-admin, a patient, a request
 *       pinned to the hospital where a provider user is a patient, two
 *       branches and none named): no seat. The handlers answer that exactly
 *       as an unmapped path, so a hospital user cannot tell the provider
 *       endpoints exist.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProviderSeatResolver {

    private static final String PATIENT = "PATIENT";

    private final ActingScopeResolver actingScopeResolver;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RoleValidator roleValidator;

    /** The caller's seat at a provider facility, or empty (answer as an unmapped path). */
    public Optional<ProviderSeat> current() {
        ActingScope scope = actingScopeResolver.current();
        UUID userId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        if (userId == null) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> providerRows = assignmentRepository.findByUser_IdAndActiveTrue(userId)
            .stream()
            .filter(row -> row.getHospital() != null && row.getHospital().isProvider())
            .filter(row -> !PATIENT.equals(UserAccountAccess.roleCode(row.getRole())))
            .toList();
        UUID facilityId = facilityOf(scope, providerRows);
        if (facilityId == null) {
            return Optional.empty();
        }
        List<UserRoleHospitalAssignment> here = providerRows.stream()
            .filter(row -> facilityId.equals(row.getHospital().getId()))
            .toList();
        if (here.isEmpty()) {
            return Optional.empty();
        }
        Hospital facility = here.get(0).getHospital();
        // Live, AND presented: the same two conditions UserAccountAccess puts
        // on a provider admin's grants and changes, so the staff page never
        // offers what the assignment service would then refuse.
        boolean admin = here.stream()
            .anyMatch(row -> RoleFacilityCompatibility.PROVIDER_ADMIN.equals(UserAccountAccess.roleCode(row.getRole())))
            && roleValidator.hasAnyAuthority(RoleFacilityCompatibility.PROVIDER_ADMIN);
        return Optional.of(new ProviderSeat(facility, userId, admin));
    }

    /**
     * The caller's seat, when they hold PROVIDER_ADMIN there (a live active
     * assignment) and present the role; empty otherwise.
     */
    public Optional<ProviderSeat> currentAdmin() {
        return current().filter(ProviderSeat::admin);
    }

    private static UUID facilityOf(ActingScope scope, List<UserRoleHospitalAssignment> providerRows) {
        if (scope instanceof ActingScope.Pinned pinned) {
            return pinned.hospitalId();
        }
        if (scope instanceof ActingScope.Refused(ActingScope.Reason reason) && reason == ActingScope.Reason.AMBIGUOUS) {
            Set<UUID> facilities = providerRows.stream()
                .map(row -> row.getHospital().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            return facilities.size() == 1 ? facilities.iterator().next() : null;
        }
        return null;
    }
}
