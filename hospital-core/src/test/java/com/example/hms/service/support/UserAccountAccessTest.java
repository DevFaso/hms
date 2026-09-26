package com.example.hms.service.support;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The rules of {@link UserAccountAccess}, one decision at a time. The
 * end-to-end proof through the real filter chain is
 * {@code UserEndpointAuthorizationIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAccountAccessTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    private UserAccountAccess access;

    private final Hospital hospitalA = hospital();
    private final Hospital hospitalB = hospital();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = new UserAccountAccess(assignmentRepository, patientRepository, registrationRepository,
            staffRepository, new TenantContextAccessor());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    // ---------------------------------------------------------------- helpers

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return h;
    }

    private static Role role(String code) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setCode(code);
        r.setName(code);
        return r;
    }

    private static UserRoleHospitalAssignment assignment(String roleCode, Hospital hospital, boolean active) {
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(role(roleCode));
        a.setHospital(hospital);
        a.setActive(active);
        a.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return a;
    }

    private static User account(UUID id) {
        User u = new User();
        u.setId(id);
        u.setUsername("u-" + id.toString().substring(0, 8));
        u.setUserRoles(new HashSet<>());
        u.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return u;
    }

    /** Sign in as the caller with these authorities; superAdmin is the context's discrete flag. */
    private void signIn(boolean superAdmin, String... authorities) {
        var details = new CustomUserDetails(account(callerId), AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .superAdmin(superAdmin)
            .build());
    }

    private void callerHolds(UserRoleHospitalAssignment... assignments) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(java.util.Arrays.stream(assignments).filter(a -> Boolean.TRUE.equals(a.getActive())).toList());
    }

    private User targetWith(UserRoleHospitalAssignment... assignments) {
        User target = account(UUID.randomUUID());
        when(assignmentRepository.findByUserId(target.getId())).thenReturn(List.of(assignments));
        return target;
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("administering another account")
    class Administer {

        @Test
        @DisplayName("a patient administers nobody")
        void patientAdministersNobody() {
            signIn(false, "ROLE_PATIENT");
            callerHolds(assignment("ROLE_PATIENT", hospitalA, true));

            assertThat(access.canAdminister(targetWith(assignment("ROLE_SUPER_ADMIN", null, true)))).isFalse();
            assertThat(access.canAdminister(targetWith(assignment("ROLE_DOCTOR", hospitalA, true)))).isFalse();
        }

        @Test
        @DisplayName("the super-admin flag administers anyone")
        void superAdminAdministersAnyone() {
            signIn(true, "ROLE_SUPER_ADMIN");

            assertThat(access.canAdminister(targetWith(assignment("ROLE_SUPER_ADMIN", null, true)))).isTrue();
        }

        @Test
        @DisplayName("a ROLE_SUPER_ADMIN authority without the context's flag is not a super-admin")
        void inflatedAuthorityIsNotSuperAdmin() {
            signIn(false, "ROLE_SUPER_ADMIN");

            assertThat(access.canAdminister(targetWith(assignment("ROLE_DOCTOR", hospitalA, true)))).isFalse();
        }

        @Test
        @DisplayName("a hospital admin administers staff whose every assignment is at their hospital")
        void hospitalAdminAdministersOwnStaff() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(access.canAdminister(targetWith(
                assignment("ROLE_DOCTOR", hospitalA, true), assignment("ROLE_NURSE", hospitalA, false)))).isTrue();
        }

        @Test
        @DisplayName("…but not an account assigned at another hospital, even once")
        void hospitalAdminStopsAtTheirHospital() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(access.canAdminister(targetWith(assignment("ROLE_DOCTOR", hospitalB, true)))).isFalse();
            // A patient here who is an admin at B would otherwise be taken over from A.
            assertThat(access.canAdminister(targetWith(
                assignment("ROLE_PATIENT", hospitalA, true), assignment("ROLE_HOSPITAL_ADMIN", hospitalB, true))))
                .isFalse();
        }

        @Test
        @DisplayName("…nor any super-admin, by assignment or by global role")
        void hospitalAdminNeverTouchesSuperAdmin() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(access.canAdminister(targetWith(
                assignment("ROLE_DOCTOR", hospitalA, true), assignment("ROLE_SUPER_ADMIN", hospitalA, false))))
                .isFalse();

            User globalSuperAdmin = targetWith(assignment("ROLE_DOCTOR", hospitalA, true));
            UserRole link = new UserRole();
            link.setUser(globalSuperAdmin);
            link.setRole(role("ROLE_SUPER_ADMIN"));
            globalSuperAdmin.getUserRoles().add(link);
            assertThat(access.canAdminister(globalSuperAdmin)).isFalse();
        }

        @Test
        @DisplayName("…nor a patient assigned here but registered at another hospital (any status)")
        void hospitalAdminStopsAtAPatientsOtherRegistrations() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));
            User patient = targetWith(assignment("ROLE_PATIENT", hospitalA, true));
            when(patientRepository.existsByUserId(patient.getId())).thenReturn(true);

            when(registrationRepository.findHospitalIdsByPatientUserId(patient.getId()))
                .thenReturn(List.of(hospitalA.getId(), hospitalB.getId()));
            assertThat(access.canAdminister(patient)).isFalse();
            assertThat(access.canDelete(patient)).isFalse();

            when(registrationRepository.findHospitalIdsByPatientUserId(patient.getId()))
                .thenReturn(List.of(hospitalA.getId()));
            assertThat(access.canAdminister(patient)).isTrue();
        }

        @Test
        @DisplayName("…nor an account with no assignment at all (a soft delete removes them)")
        void hospitalAdminCannotReachUnassignedAccount() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(access.canAdminister(targetWith())).isFalse();
        }

        @Test
        @DisplayName("the hospital-admin authority without an active admin assignment administers nothing")
        void inactiveAdminAssignmentDoesNotCount() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, false), assignment("ROLE_DOCTOR", hospitalA, true));

            assertThat(access.canAdminister(targetWith(assignment("ROLE_NURSE", hospitalA, true)))).isFalse();
        }
    }

    @Nested
    @DisplayName("viewing an account")
    class View {

        @Test
        @DisplayName("anyone views themselves, a patient included")
        void selfIsVisible() {
            signIn(false, "ROLE_PATIENT");
            User self = account(callerId);

            assertThat(access.isSelf(self)).isTrue();
            assertThat(access.canView(self)).isTrue();
        }

        @Test
        @DisplayName("a doctor does not view another account; a hospital admin views one assigned at their hospital")
        void othersNeedAnAdministrator() {
            signIn(false, "ROLE_DOCTOR");
            callerHolds(assignment("ROLE_DOCTOR", hospitalA, true));
            User colleague = targetWith(assignment("ROLE_NURSE", hospitalA, true), assignment("ROLE_NURSE", hospitalB, true));
            assertThat(access.canView(colleague)).isFalse();

            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));
            assertThat(access.canView(colleague)).isTrue();
            assertThat(access.canView(targetWith(assignment("ROLE_NURSE", hospitalB, true)))).isFalse();
        }

        @Test
        @DisplayName("an unauthenticated context is nobody")
        void noAuthenticationIsNobody() {
            assertThat(access.currentUserId()).isEmpty();
            assertThat(access.canView(account(UUID.randomUUID()))).isFalse();
        }
    }

    @Nested
    @DisplayName("the directory")
    class Directory {

        @Test
        @DisplayName("a patient is refused, even carrying Keycloak's default realm roles")
        void patientIsRefused() {
            signIn(false, "ROLE_PATIENT", "ROLE_OFFLINE_ACCESS", "ROLE_DEFAULT-ROLES-HMS");
            callerHolds(assignment("ROLE_PATIENT", hospitalA, true));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("staff in any role other than PATIENT, and the super-admin, are admitted")
        void staffIsAdmitted() {
            signIn(false, "ROLE_PHARMACIST");
            callerHolds(assignment("ROLE_PHARMACIST", hospitalA, true));
            assertThatCode(access::requireDirectoryAccess).doesNotThrowAnyException();

            signIn(true, "ROLE_SUPER_ADMIN");
            assertThatCode(access::requireDirectoryAccess).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a staff token whose only staff assignment is inactive is refused")
        void inactiveStaffIsRefused() {
            signIn(false, "ROLE_NURSE");
            callerHolds(assignment("ROLE_NURSE", hospitalA, false));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("discarding an unclaimed patient account (patient-form's compensation)")
    class DiscardUnclaimed {

        private User orphan;

        @BeforeEach
        void receptionistAtA() {
            signIn(false, "ROLE_RECEPTIONIST");
            callerHolds(assignment("ROLE_RECEPTIONIST", hospitalA, true));
            orphan = targetWith(assignment("ROLE_PATIENT", hospitalA, false));
            when(patientRepository.existsByUserId(orphan.getId())).thenReturn(false);
            when(staffRepository.existsByUserId(orphan.getId())).thenReturn(false);
        }

        @Test
        @DisplayName("the account the failed registration just made qualifies")
        void justCreatedOrphanQualifies() {
            assertThat(access.canDelete(orphan)).isTrue();
        }

        @Test
        @DisplayName("a doctor at A who is a patient at B cannot discard B's fresh account")
        void patientAtAnotherHospitalCannotDiscard() {
            signIn(false, "ROLE_DOCTOR", "ROLE_PATIENT");
            callerHolds(assignment("ROLE_DOCTOR", hospitalA, true), assignment("ROLE_PATIENT", hospitalB, true));
            User freshAtB = targetWith(assignment("ROLE_PATIENT", hospitalB, false));
            when(patientRepository.existsByUserId(freshAtB.getId())).thenReturn(false);
            when(staffRepository.existsByUserId(freshAtB.getId())).thenReturn(false);

            assertThat(access.canDelete(freshAtB)).isFalse();
            // The same doctor still discards the one at A, where they register.
            assertThat(access.canDelete(orphan)).isTrue();
        }

        @Test
        @DisplayName("a caller without a registrar role cannot discard it")
        void nonRegistrarCannot() {
            signIn(false, "ROLE_PATIENT");
            assertThat(access.canDelete(orphan)).isFalse();
        }

        @Test
        @DisplayName("an account with a patient row is a real patient, not an orphan")
        void patientRowDisqualifies() {
            when(patientRepository.existsByUserId(orphan.getId())).thenReturn(true);
            assertThat(access.canDelete(orphan)).isFalse();
        }

        @Test
        @DisplayName("an account with a staff row disqualifies")
        void staffRowDisqualifies() {
            when(staffRepository.existsByUserId(orphan.getId())).thenReturn(true);
            assertThat(access.canDelete(orphan)).isFalse();
        }

        @Test
        @DisplayName("an account that has signed in, by password or by SSO, disqualifies")
        void signedInDisqualifies() {
            orphan.setLastLoginAt(LocalDateTime.now());
            assertThat(access.canDelete(orphan)).isFalse();
            orphan.setLastLoginAt(null);
            orphan.setLastOidcLoginAt(java.time.OffsetDateTime.now());
            assertThat(access.canDelete(orphan)).isFalse();
        }

        @Test
        @DisplayName("an assignment older than the window disqualifies; an old account re-registered with a fresh one does not")
        void staleAssignmentDisqualifies() {
            UserRoleHospitalAssignment stale = assignment("ROLE_PATIENT", hospitalA, false);
            stale.setCreatedAt(LocalDateTime.now().minus(UserAccountAccess.UNCLAIMED_ACCOUNT_WINDOW).minusMinutes(1));
            User old = targetWith(stale);
            assertThat(access.canDelete(old)).isFalse();

            // A previous compensation soft-deleted this account (and its
            // assignments); admin-register restored the old row and assigned it anew.
            orphan.setCreatedAt(LocalDateTime.now().minusDays(3));
            assertThat(access.canDelete(orphan)).isTrue();
        }

        @Test
        @DisplayName("any non-patient role, or a patient assignment at another hospital, disqualifies")
        void otherRolesOrHospitalsDisqualify() {
            User staff = targetWith(assignment("ROLE_PATIENT", hospitalA, false), assignment("ROLE_NURSE", hospitalA, true));
            assertThat(access.canDelete(staff)).isFalse();

            User elsewhere = targetWith(assignment("ROLE_PATIENT", hospitalB, false));
            assertThat(access.canDelete(elsewhere)).isFalse();

            User globalAdmin = targetWith(assignment("ROLE_PATIENT", hospitalA, false));
            UserRole link = new UserRole();
            link.setUser(globalAdmin);
            link.setRole(role("ROLE_SUPER_ADMIN"));
            globalAdmin.getUserRoles().add(link);
            assertThat(access.canDelete(globalAdmin)).isFalse();

            assertThat(access.canDelete(targetWith())).isFalse();
        }

        @Test
        @DisplayName("a deleted account, or the caller's own, disqualifies")
        void deletedOrSelfDisqualifies() {
            orphan.setDeleted(true);
            assertThat(access.canDelete(orphan)).isFalse();

            User self = account(callerId);
            when(assignmentRepository.findByUserId(callerId)).thenReturn(List.of(assignment("ROLE_PATIENT", hospitalA, true)));
            assertThat(access.canDelete(self)).isFalse();
        }
    }
}
