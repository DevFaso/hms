package com.example.hms.service.support;

import com.example.hms.security.tenant.ActingScopeTestSupport;
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
import com.example.hms.utility.RoleValidator;
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
import java.util.Set;
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
            staffRepository, new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver()));
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
        @DisplayName("QA D190: an inactive row at A does not open a user now working at B")
        void aStaleInactiveRowDoesNotOpenAnotherHospitalsUser() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            User movedToB = targetWith(assignment("ROLE_NURSE", hospitalA, false), assignment("ROLE_NURSE", hospitalB, true));
            assertThat(access.canView(movedToB)).isFalse();

            // A pending invitation (or a former employee) active nowhere stays visible to A.
            User pending = targetWith(assignment("ROLE_NURSE", hospitalA, false));
            assertThat(access.canView(pending)).isTrue();
        }

        @Test
        @DisplayName("canView edges: no target, a super-admin's account, an inactive row nowhere administered")
        void canViewEdges() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(access.canView(null)).isFalse();
            assertThat(access.canView(targetWith(assignment("ROLE_SUPER_ADMIN", hospitalA, true)))).isFalse();
            assertThat(access.canView(targetWith(assignment("ROLE_NURSE", hospitalB, false)))).isFalse();
            assertThat(access.canView(targetWith(assignment("ROLE_NURSE", hospitalA, false),
                assignment("ROLE_NURSE", null, true)))).isFalse();
        }

        @Test
        @DisplayName("canView: a caller who administers no hospital views nobody else")
        void nonAdministratorViewsNobody() {
            signIn(false, "ROLE_NURSE");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true));
            assertThat(access.canView(account(UUID.randomUUID()))).isFalse();
        }

        @Test
        @DisplayName("an unauthenticated context is nobody")
        void noAuthenticationIsNobody() {
            assertThat(access.currentUserId()).isEmpty();
            assertThat(access.canView(account(UUID.randomUUID()))).isFalse();
        }
    }

    @Nested
    @DisplayName("the directory (administrators only, QA D790)")
    class Directory {

        @Test
        @DisplayName("a patient is refused, even carrying Keycloak's default realm roles")
        void patientIsRefused() {
            signIn(false, "ROLE_PATIENT", "ROLE_OFFLINE_ACCESS", "ROLE_DEFAULT-ROLES-HMS");
            callerHolds(assignment("ROLE_PATIENT", hospitalA, true));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0} is refused")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
            "ROLE_LAB_TECHNICIAN", "ROLE_PHARMACIST", "ROLE_NURSE", "ROLE_RECEPTIONIST", "ROLE_DOCTOR",
            "ROLE_PROVIDER_ADMIN"})
        @DisplayName("QA D790: the staff roles the tester used (and the doctor) are refused")
        void nonAdministratorStaffIsRefused(String role) {
            signIn(false, role);
            callerHolds(assignment(role, hospitalA, true));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a hospital admin, an admin and the super-admin are admitted")
        void administratorsAreAdmitted() {
            for (String role : java.util.List.of("ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN")) {
                signIn(false, role);
                callerHolds(assignment(role, hospitalA, true));
                assertThatCode(access::requireDirectoryAccess).as(role).doesNotThrowAnyException();
            }

            signIn(true, "ROLE_SUPER_ADMIN");
            assertThatCode(access::requireDirectoryAccess).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the super-admin's directory is every account")
        void superAdminSeesEveryone() {
            signIn(true, "ROLE_SUPER_ADMIN");

            UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

            assertThat(scope.everyone()).isTrue();
        }

        @Test
        @DisplayName("an inflated ROLE_SUPER_ADMIN authority without the context flag is no administrator")
        void inflatedSuperAdminAuthorityIsRefused() {
            signIn(false, "ROLE_SUPER_ADMIN", "ROLE_NURSE");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("an administrator's directory is the hospitals they administer, not where they are other staff")
        void scopeIsTheAdministeredHospitals() {
            signIn(false, "ROLE_HOSPITAL_ADMIN", "ROLE_NURSE");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true), assignment("ROLE_NURSE", hospitalB, true));

            UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

            assertThat(scope.everyone()).isFalse();
            assertThat(scope.hospitalIds()).containsExactly(hospitalA.getId());
            assertThat(scope.seesPatientContacts()).isTrue();
        }

        @Test
        @DisplayName("a global admin assignment (no hospital) is admitted with an empty scope: nobody")
        void globalAdminAssignmentSeesNobody() {
            signIn(false, "ROLE_ADMIN");
            callerHolds(assignment("ROLE_ADMIN", null, true));

            UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

            assertThat(scope.everyone()).isFalse();
            assertThat(scope.hospitalIds()).isEmpty();
        }

        @Test
        @DisplayName("an administrator whose admin assignment is inactive is refused")
        void inactiveAdministratorIsRefused() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, false));

            assertThatThrownBy(access::requireDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("the chat's colleague directory (QA D790)")
    class ColleagueDirectory {

        @Test
        @DisplayName("any active staff role is admitted, scoped to its hospitals, never with patient contacts")
        void staffIsAdmittedScoped() {
            signIn(false, "ROLE_NURSE", "ROLE_DOCTOR");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true), assignment("ROLE_DOCTOR", hospitalB, true));

            UserAccountAccess.DirectoryScope scope = access.requireColleagueDirectoryAccess();

            assertThat(scope.everyone()).isFalse();
            assertThat(scope.hospitalIds()).containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
            assertThat(scope.seesPatientContacts()).isFalse();
        }

        @Test
        @DisplayName("the caller's own PATIENT assignment elsewhere does not widen it")
        void patientAssignmentDoesNotWiden() {
            signIn(false, "ROLE_NURSE", "ROLE_PATIENT");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true), assignment("ROLE_PATIENT", hospitalB, true));

            assertThat(access.requireColleagueDirectoryAccess().hospitalIds()).containsExactly(hospitalA.getId());
        }

        @Test
        @DisplayName("a patient, and staff whose only assignment is inactive, are refused")
        void patientsAndInactiveStaffAreRefused() {
            signIn(false, "ROLE_PATIENT");
            callerHolds(assignment("ROLE_PATIENT", hospitalA, true));
            assertThatThrownBy(access::requireColleagueDirectoryAccess).isInstanceOf(AccessDeniedException.class);

            signIn(false, "ROLE_NURSE");
            callerHolds(assignment("ROLE_NURSE", hospitalA, false));
            assertThatThrownBy(access::requireColleagueDirectoryAccess).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the super-admin sees every colleague, still without patient contacts")
        void superAdmin() {
            signIn(true, "ROLE_SUPER_ADMIN");

            UserAccountAccess.DirectoryScope scope = access.requireColleagueDirectoryAccess();

            assertThat(scope.everyone()).isTrue();
            assertThat(scope.seesPatientContacts()).isFalse();
        }
    }

    @Nested
    @DisplayName("a hospital admin and a peer admin")
    class PeerAdmin {

        @Test
        @DisplayName("an account holding HOSPITAL_ADMIN or ADMIN, active or not, is the super-admin's only")
        void peerAdminsAreSuperAdminOnly() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            User peer = targetWith(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));
            User formerPeer = targetWith(assignment("ROLE_NURSE", hospitalA, true),
                assignment("ROLE_HOSPITAL_ADMIN", hospitalA, false));
            User backOffice = targetWith(assignment("ROLE_ADMIN", hospitalA, true));
            assertThat(access.canAdminister(peer)).isFalse();
            assertThat(access.canAdminister(formerPeer)).isFalse();
            assertThat(access.canAdminister(backOffice)).isFalse();
            assertThat(access.canDelete(peer)).isFalse();

            signIn(true, "ROLE_SUPER_ADMIN");
            assertThat(access.canAdminister(peer)).isTrue();
        }
    }

    @Nested
    @DisplayName("granting roles (admin-register)")
    class Grant {

        private UUID grant(String role, Hospital at) {
            return access.requireMayGrant(Set.of(role)).requireAt(at == null ? null : at.getId());
        }

        @Test
        @DisplayName("the super-admin grants any role, at any hospital or none")
        void superAdminGrantsAnything() {
            signIn(true, "ROLE_SUPER_ADMIN");

            assertThat(grant("SUPER_ADMIN", null)).isNull();
            assertThat(grant("ROLE_HOSPITAL_ADMIN", hospitalB)).isEqualTo(hospitalB.getId());
        }

        @Test
        @DisplayName("a hospital admin grants non-admin roles at their hospital only")
        void hospitalAdminGrantsNonAdminRolesAtHome() {
            signIn(false, "ROLE_HOSPITAL_ADMIN");
            callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

            assertThat(grant("NURSE", hospitalA)).isEqualTo(hospitalA.getId());
            assertThat(grant("PATIENT", hospitalA)).isEqualTo(hospitalA.getId());
            for (String adminRole : new String[] {"SUPER_ADMIN", "HOSPITAL_ADMIN", "ADMIN", "ROLE_HOSPITAL_ADMIN"}) {
                assertThatThrownBy(() -> grant(adminRole, hospitalA)).as(adminRole)
                    .isInstanceOf(AccessDeniedException.class);
            }
            assertThatThrownBy(() -> grant("NURSE", hospitalB)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> grant("NURSE", null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> access.requireMayGrant(Set.of("NURSE", "HOSPITAL_ADMIN")))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a nurse grants PATIENT only, at a hospital where they register; SUPER_ADMIN never")
        void registrarGrantsPatientOnly() {
            signIn(false, "ROLE_NURSE");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true));

            assertThat(grant("PATIENT", hospitalA)).isEqualTo(hospitalA.getId());
            // Refused on the role set alone, before any hospital is resolved.
            assertThatThrownBy(() -> access.requireMayGrant(Set.of("SUPER_ADMIN")))
                .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> grant("DOCTOR", hospitalA)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> grant("HOSPITAL_ADMIN", hospitalA)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> grant("PATIENT", hospitalB)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> grant("PATIENT", null)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a nurse's own patient assignment elsewhere is not a place they register")
        void ownPatientAssignmentIsNotARegistrarHospital() {
            signIn(false, "ROLE_NURSE", "ROLE_PATIENT");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true), assignment("ROLE_PATIENT", hospitalB, true));

            assertThatThrownBy(() -> grant("PATIENT", hospitalB)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("QA D55: a surgeon (a DOCTOR by RoleExpansion) is not a patient registrar")
        void surgeonRegistersPatients() {
            signIn(false, "ROLE_SURGEON");
            callerHolds(assignment("ROLE_SURGEON", hospitalA, true));

            assertThatThrownBy(() -> grant("PATIENT", hospitalA)).isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a patient grants nothing")
        void patientGrantsNothing() {
            signIn(false, "ROLE_PATIENT");
            callerHolds(assignment("ROLE_PATIENT", hospitalA, true));

            assertThatThrownBy(() -> grant("PATIENT", hospitalA)).isInstanceOf(AccessDeniedException.class);
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
        @DisplayName("a nurse at A who is a patient at B cannot discard B's fresh account")
        void patientAtAnotherHospitalCannotDiscard() {
            signIn(false, "ROLE_NURSE", "ROLE_PATIENT");
            callerHolds(assignment("ROLE_NURSE", hospitalA, true), assignment("ROLE_PATIENT", hospitalB, true));
            User freshAtB = targetWith(assignment("ROLE_PATIENT", hospitalB, false));
            when(patientRepository.existsByUserId(freshAtB.getId())).thenReturn(false);
            when(staffRepository.existsByUserId(freshAtB.getId())).thenReturn(false);

            assertThat(access.canDelete(freshAtB)).isFalse();
            // The same nurse still discards the one at A, where they register.
            assertThat(access.canDelete(orphan)).isTrue();
        }

        @Test
        @DisplayName("QA D55: a surgeon registers no patient, so has no orphan to discard")
        void surgeonCompensates() {
            signIn(false, "ROLE_SURGEON");
            callerHolds(assignment("ROLE_SURGEON", hospitalA, true));

            assertThat(access.canDelete(orphan)).isFalse();
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
