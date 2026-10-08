package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

/** The role/facility table (provider plan §3.2) and one kind of facility per user (§3.2a). */
@ExtendWith(MockitoExtension.class)
class FacilityAssignmentGuardTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @InjectMocks private FacilityAssignmentGuard guard;

    @ParameterizedTest(name = "{0} at a {1}: {2}")
    @CsvSource({
        "ROLE_DOCTOR, HOSPITAL, true",
        "ROLE_PHARMACIST, HOSPITAL, true",
        "ROLE_PATIENT, HOSPITAL, true",
        "ROLE_PROVIDER_ADMIN, HOSPITAL, false",
        "PROVIDER_ADMIN, PHARMACY, true",
        "ROLE_PHARMACIST, PHARMACY, true",
        "ROLE_PHARMACY_VERIFIER, PHARMACY, false",
        "ROLE_DOCTOR, PHARMACY, false",
        "ROLE_PATIENT, PHARMACY, false",
        "ROLE_LAB_SCIENTIST, PHARMACY, false",
        "ROLE_PROVIDER_ADMIN, LABORATORY, true",
        "ROLE_LAB_TECHNICIAN, LABORATORY, true",
        "ROLE_LAB_SCIENTIST, LABORATORY, true",
        "ROLE_LAB_MANAGER, LABORATORY, true",
        "lab_director, LABORATORY, true",
        "ROLE_PHARMACIST, LABORATORY, false",
        "ROLE_NURSE, LABORATORY, false",
        "ROLE_HOSPITAL_ADMIN, LABORATORY, false",
    })
    void theTable(String role, FacilityType type, boolean allowed) {
        assertThat(RoleFacilityCompatibility.isCompatible(role, type)).isEqualTo(allowed);
        Executable check = () -> guard.requireCompatible(role, facility(type));
        if (allowed) {
            assertThatCode(check::execute).doesNotThrowAnyException();
        } else {
            assertRefused(check, FacilityAssignmentGuard.MSG_INCOMPATIBLE);
        }
    }

    @Test
    @DisplayName("a facility with no type is a hospital; a global row is never refused for compatibility")
    void nullTypeAndGlobalRow() {
        assertThat(RoleFacilityCompatibility.isCompatible("ROLE_PROVIDER_ADMIN", null)).isFalse();
        assertThat(RoleFacilityCompatibility.isCompatible("ROLE_DOCTOR", null)).isTrue();
        assertThatCode(() -> guard.requireCompatible("ROLE_PROVIDER_ADMIN", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("admin-register's form: the roles at a facility known by id")
    void rolesAtAFacilityId() {
        Hospital pharmacy = facility(FacilityType.PHARMACY);
        when(hospitalRepository.findById(pharmacy.getId())).thenReturn(Optional.of(pharmacy));

        assertThatCode(() -> guard.requireCompatible(Set.of("PHARMACIST"), pharmacy.getId()))
            .doesNotThrowAnyException();
        assertRefused(() -> guard.requireCompatible(Set.of("PHARMACIST", "DOCTOR"), pharmacy.getId()),
            FacilityAssignmentGuard.MSG_INCOMPATIBLE);
        assertThatCode(() -> guard.requireCompatible(Set.of("DOCTOR"), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("mixed: an active hospital row refuses a provider row, and the reverse")
    void mixed() {
        User user = user();
        Hospital hospital = facility(FacilityType.HOSPITAL);
        Hospital pharmacy = facility(FacilityType.PHARMACY);
        when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(row(hospital)));

        assertRefused(() -> guard.requireSingleFacilityKind(user, "ROLE_PHARMACIST", pharmacy, null), FacilityAssignmentGuard.MSG_MIXED);
        assertThatCode(() -> guard.requireSingleFacilityKind(user, "ROLE_NURSE", facility(FacilityType.HOSPITAL), null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the row being changed does not count against itself; a hospital-less row never counts")
    void exclusions() {
        User user = user();
        UserRoleHospitalAssignment self = row(facility(FacilityType.HOSPITAL));
        when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(self, row(null)));

        assertThatCode(() -> guard.requireSingleFacilityKind(user, "ROLE_LAB_SCIENTIST", facility(FacilityType.LABORATORY), self.getId()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PATIENT rows are outside the rule: a pharmacist becomes a patient at a hospital")
    void pharmacistBecomesAPatientAtAHospital() {
        User user = user();
        // Lenient: a PATIENT row is decided before any lookup.
        org.mockito.Mockito.lenient().when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(row("ROLE_PHARMACIST", facility(FacilityType.PHARMACY))));

        assertThatCode(() -> guard.requireSingleFacilityKind(user, "ROLE_PATIENT", facility(FacilityType.HOSPITAL), null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("and a hospital-bound PATIENT row does not block becoming a pharmacist")
    void hospitalPatientBecomesAPharmacist() {
        User user = user();
        when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(row("ROLE_PATIENT", facility(FacilityType.HOSPITAL))));

        assertThatCode(() -> guard.requireSingleFacilityKind(user, "ROLE_PHARMACIST", facility(FacilityType.PHARMACY), null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("but a nurse at a hospital cannot also be a pharmacist")
    void nurseCannotAlsoBeAPharmacist() {
        User user = user();
        when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(row("ROLE_NURSE", facility(FacilityType.HOSPITAL))));

        assertRefused(() -> guard.requireSingleFacilityKind(user, "ROLE_PHARMACIST", facility(FacilityType.PHARMACY), null),
            FacilityAssignmentGuard.MSG_MIXED);
    }

    @Test
    @DisplayName("requireAssignable runs both checks")
    void both() {
        User user = user();
        when(assignmentRepository.findByUser_IdAndActiveTrue(user.getId()))
            .thenReturn(List.of(row(facility(FacilityType.HOSPITAL))));

        assertRefused(() -> guard.requireAssignable(user, "ROLE_DOCTOR", facility(FacilityType.PHARMACY), null),
            FacilityAssignmentGuard.MSG_INCOMPATIBLE);
        assertRefused(() -> guard.requireAssignable(user, "ROLE_PHARMACIST", facility(FacilityType.PHARMACY), null),
            FacilityAssignmentGuard.MSG_MIXED);
    }

    private static void assertRefused(Executable call, String key) {
        Throwable thrown = catchThrowable(call::execute);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getMessageKey()).isEqualTo(key);
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }

    private static User user() {
        User u = new User();
        u.setId(UUID.randomUUID());
        return u;
    }

    private static UserRoleHospitalAssignment row(Hospital hospital) {
        return row("ROLE_DOCTOR", hospital);
    }

    private static UserRoleHospitalAssignment row(String roleCode, Hospital hospital) {
        Role role = new Role();
        role.setCode(roleCode);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(role);
        a.setHospital(hospital);
        a.setActive(true);
        return a;
    }
}
