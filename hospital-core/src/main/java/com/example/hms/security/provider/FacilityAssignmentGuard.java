package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The service-level control of plan §3.2 and §3.2a: a 400, thrown as a
 * {@link BusinessException} BEFORE anything is saved, so the multi-scope loop
 * and the bulk import record it as a per-line failure instead of a 500.
 *
 * <ul>
 *   <li><b>Compatibility</b> ({@code role.facility.incompatible}): the role
 *       must be one {@link RoleFacilityCompatibility} allows at the facility's
 *       type. PROVIDER_ADMIN is never held at a hospital; a pharmacy takes
 *       PROVIDER_ADMIN and PHARMACIST only; a laboratory PROVIDER_ADMIN and
 *       the four LAB_* roles.</li>
 *   <li><b>One kind of facility per user</b> ({@code role.facility.mixed}):
 *       creating or activating a STAFF assignment at a facility is refused
 *       when the user holds an ACTIVE staff assignment at a facility of
 *       another type. Someone who really works in both places gets two
 *       accounts. PATIENT assignments are outside the rule both ways: they
 *       never count and are never refused, whatever hospital they are bound
 *       to (a registrar's PATIENT row carries the registering hospital, not
 *       a null one), so a pharmacist can be a patient at a hospital. A
 *       hospital-less row (the SUPER_ADMIN) is at no facility and never
 *       counts either. The confinement filter (P1-T6) must therefore admit a
 *       provider user's patient self-service paths for a hospital-bound
 *       PATIENT row too, not only for a global one.</li>
 * </ul>
 *
 * <p>The JPA backstop on {@code UserRoleHospitalAssignment} repeats the
 * compatibility rule only; it sees one row, so the mixed-facility rule has
 * no backstop and every assignment entry point calls this guard instead.
 */
@Component
@RequiredArgsConstructor
public class FacilityAssignmentGuard {

    public static final String MSG_INCOMPATIBLE = "role.facility.incompatible";
    public static final String MSG_MIXED = "role.facility.mixed";
    /** QA D789: a translated 409 for a role granted, activated or re-invited at a closed hospital. */
    public static final String MSG_HOSPITAL_CLOSED = "assignment.hospital.closed";

    /**
     * The lifecycle states in which a hospital takes no assignment, no
     * activation and no invitation. One set, read by {@link #isClosed} and by
     * the database claim of the public activation re-send.
     */
    public static final Set<HospitalLifecycleState> CLOSED_STATES = Set.of(
        HospitalLifecycleState.ARCHIVED, HospitalLifecycleState.PENDING_PURGE, HospitalLifecycleState.PURGED);

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final HospitalRepository hospitalRepository;

    /**
     * QA D789: an ARCHIVED hospital (and one on its way out: PENDING_PURGE,
     * PURGED) takes no new role, no activation and no re-invitation. A
     * SUSPENDED one still does: a suspension is lifted, and the staff it
     * comes back to are prepared while it lasts. Static so every entry point
     * that writes an assignment row calls the same rule, injected or not;
     * {@code UserRoleHospitalAssignment}'s {@code @PrePersist} is the backstop.
     *
     * @throws ConflictException {@code assignment.hospital.closed}; a
     *                           {@code null} hospital (a global row) is never refused
     */
    public static void requireOpenForAssignment(Hospital hospital) {
        if (hospital != null && isClosed(hospital.getLifecycleState())) {
            // Resolved here, not by the handler: the multi-scope and bulk-import
            // loops report a conflict's message per line as it stands.
            throw new ConflictException(com.example.hms.utility.MessageUtil.resolveOrRaw(MSG_HOSPITAL_CLOSED));
        }
    }

    /** The lifecycle states in which a hospital takes no assignment. */
    public static boolean isClosed(HospitalLifecycleState state) {
        return state != null && CLOSED_STATES.contains(state);
    }

    /**
     * @throws BusinessException {@code role.facility.incompatible} when the
     *                           role may not be held at this facility; a
     *                           {@code null} hospital (a global row) is never refused here
     */
    public void requireCompatible(String roleCode, Hospital hospital) {
        if (hospital != null && !RoleFacilityCompatibility.isCompatible(roleCode, hospital.getFacilityType())) {
            throw new BusinessException(MSG_INCOMPATIBLE);
        }
    }

    /**
     * The same rule for a set of roles at a facility known by id, for
     * admin-register, which resolves the hospital as an id. An id that names
     * no row is left to the caller's own not-found answer. QA D789: and an
     * archived hospital takes no one ({@link #requireOpenForAssignment(Hospital)},
     * a 409), whatever the roles.
     */
    public void requireCompatible(Collection<String> roleCodes, UUID hospitalId) {
        if (hospitalId == null) {
            return;
        }
        hospitalRepository.findById(hospitalId).ifPresent(hospital -> {
            requireOpenForAssignment(hospital);
            if (roleCodes != null) {
                for (String roleCode : roleCodes) {
                    requireCompatible(roleCode, hospital);
                }
            }
        });
    }

    /**
     * @param roleCode             the role of the row being created or activated;
     *                             a PATIENT row is never refused
     * @param excludedAssignmentId the row being changed or activated, which
     *                             must not count against itself; {@code null} for a new row
     * @throws BusinessException {@code role.facility.mixed} when the user
     *                           actively holds a staff assignment at a
     *                           facility of another type than {@code hospital}
     */
    public void requireSingleFacilityKind(User user, String roleCode, Hospital hospital, UUID excludedAssignmentId) {
        if (user == null || user.getId() == null || hospital == null || isPatient(roleCode)) {
            return;
        }
        FacilityType wanted = FacilityType.orHospital(hospital.getFacilityType());
        boolean mixed = assignmentRepository.findByUser_IdAndActiveTrue(user.getId()).stream()
            .filter(existing -> excludedAssignmentId == null || !excludedAssignmentId.equals(existing.getId()))
            .filter(existing -> !isPatient(roleCodeOf(existing)))
            .map(UserRoleHospitalAssignment::getHospital)
            .filter(Objects::nonNull)
            .anyMatch(other -> FacilityType.orHospital(other.getFacilityType()) != wanted);
        if (mixed) {
            throw new BusinessException(MSG_MIXED);
        }
    }

    /** Both checks, for a row at {@code hospital} in {@code roleCode}. */
    public void requireAssignable(User user, String roleCode, Hospital hospital, UUID excludedAssignmentId) {
        requireCompatible(roleCode, hospital);
        requireSingleFacilityKind(user, roleCode, hospital, excludedAssignmentId);
    }

    private static String roleCodeOf(UserRoleHospitalAssignment assignment) {
        if (assignment.getRole() == null) {
            return null;
        }
        String code = assignment.getRole().getCode();
        return code != null ? code : assignment.getRole().getName();
    }

    private static boolean isPatient(String roleCode) {
        return "PATIENT".equals(RoleFacilityCompatibility.bare(roleCode));
    }
}
