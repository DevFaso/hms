package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;
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
 *       creating or activating an assignment at a facility is refused when the
 *       user holds an ACTIVE assignment at a facility of another type. A
 *       hospital-less row (the global PATIENT, the SUPER_ADMIN) is at no
 *       facility and never counts. Someone who really works in both places
 *       gets two accounts.</li>
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

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final HospitalRepository hospitalRepository;

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
     * no row is left to the caller's own not-found answer.
     */
    public void requireCompatible(Collection<String> roleCodes, UUID hospitalId) {
        if (hospitalId == null || roleCodes == null || roleCodes.isEmpty()) {
            return;
        }
        hospitalRepository.findById(hospitalId).ifPresent(hospital -> {
            for (String roleCode : roleCodes) {
                requireCompatible(roleCode, hospital);
            }
        });
    }

    /**
     * @param excludedAssignmentId the row being changed or activated, which
     *                             must not count against itself; {@code null} for a new row
     * @throws BusinessException {@code role.facility.mixed} when the user
     *                           actively holds an assignment at a facility of
     *                           another type than {@code hospital}
     */
    public void requireSingleFacilityKind(User user, Hospital hospital, UUID excludedAssignmentId) {
        if (user == null || user.getId() == null || hospital == null) {
            return;
        }
        FacilityType wanted = FacilityType.orHospital(hospital.getFacilityType());
        boolean mixed = assignmentRepository.findByUser_IdAndActiveTrue(user.getId()).stream()
            .filter(existing -> excludedAssignmentId == null || !excludedAssignmentId.equals(existing.getId()))
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
        requireSingleFacilityKind(user, hospital, excludedAssignmentId);
    }
}
