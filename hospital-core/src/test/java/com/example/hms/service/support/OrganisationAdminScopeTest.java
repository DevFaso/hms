package com.example.hms.service.support;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Platform admin rules 2026-11-20, rule 1a: an organisation admin (ROLE_ADMIN)
 * registers staff and works assignments at every hospital of its own
 * organisation, and at no other. The scope is derived from the caller's own
 * ADMIN assignments; nothing a request says can widen it.
 *
 * <p>Organisation 1 owns hospitals A and B; organisation 2 owns hospital C.
 * The caller is the admin of organisation 1, with its ADMIN assignment at A.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganisationAdminScopeTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    private UserAccountAccess access;

    private final Hospital hospitalA = hospital();
    private final Hospital hospitalB = hospital();
    private final Hospital hospitalC = hospital();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = new UserAccountAccess(assignmentRepository, patientRepository, registrationRepository,
            staffRepository, new RoleValidator(assignmentRepository, ActingScopeTestSupport.resolver()));
        // Organisation 1 = {A, B}: asked for the organisation of A (or of A and B), the answer is A and B.
        when(assignmentRepository.findClinicalHospitalIdsInOrganisationsOf(argThat(ids ->
                ids != null && !ids.isEmpty()
                    && Set.of(hospitalA.getId(), hospitalB.getId()).containsAll(ids))))
            .thenReturn(Set.of(hospitalA.getId(), hospitalB.getId()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
    }

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return h;
    }

    private static UserRoleHospitalAssignment assignment(String roleCode, Hospital hospital, boolean active) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(roleCode);
        role.setName(roleCode);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(role);
        a.setHospital(hospital);
        a.setActive(active);
        a.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return a;
    }

    private void signIn(String... authorities) {
        User caller = new User();
        caller.setId(callerId);
        caller.setUsername("org-admin");
        caller.setUserRoles(new HashSet<>());
        var details = new CustomUserDetails(caller, AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(callerId).superAdmin(false).build());
    }

    private void callerHolds(UserRoleHospitalAssignment... assignments) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(Arrays.stream(assignments).filter(a -> Boolean.TRUE.equals(a.getActive())).toList());
    }

    private void organisationAdminOfOne() {
        signIn("ROLE_ADMIN");
        callerHolds(assignment("ROLE_ADMIN", hospitalA, true));
    }

    private UUID grant(String role, Hospital at) {
        return access.requireMayGrant(Set.of(role)).requireAt(at.getId());
    }

    // ── scope ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the scope is every hospital of the caller's organisation, and no other")
    void scopeIsTheOrganisation() {
        organisationAdminOfOne();

        assertThat(access.organisationHospitalIds())
            .containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId())
            .doesNotContain(hospitalC.getId());
    }

    @Test
    @DisplayName("the scope is asked about the caller's OWN admin hospital, never about a hospital the request names")
    void scopeIsDerivedFromTheCallersAssignments() {
        organisationAdminOfOne();

        access.organisationHospitalIds();

        verify(assignmentRepository).findClinicalHospitalIdsInOrganisationsOf(Set.of(hospitalA.getId()));
    }

    @Test
    @DisplayName("an INACTIVE admin assignment, or a token that does not present the role, gives no scope")
    void noScopeWithoutALiveRole() {
        signIn("ROLE_ADMIN");
        callerHolds(assignment("ROLE_ADMIN", hospitalA, false));
        assertThat(access.organisationHospitalIds()).isEmpty();

        // The assignment exists, but the caller signed in as a nurse.
        signIn("ROLE_NURSE");
        callerHolds(assignment("ROLE_ADMIN", hospitalA, true), assignment("ROLE_NURSE", hospitalA, true));
        assertThat(access.organisationHospitalIds()).isEmpty();

        verify(assignmentRepository, never()).findClinicalHospitalIdsInOrganisationsOf(any());
    }

    @Test
    @DisplayName("a hospital admin is not an organisation admin: its scope stays its own hospital")
    void hospitalAdminGetsNoOrganisationScope() {
        signIn("ROLE_HOSPITAL_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));

        assertThat(access.organisationHospitalIds()).isEmpty();
        assertThat(grant("NURSE", hospitalA)).isEqualTo(hospitalA.getId());
        assertThatThrownBy(() -> grant("NURSE", hospitalB)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a hospital attached to no organisation stands alone: its admin administers that hospital only")
    void hospitalWithoutOrganisationStandsAlone() {
        Hospital lone = hospital();
        signIn("ROLE_ADMIN");
        callerHolds(assignment("ROLE_ADMIN", lone, true));
        when(assignmentRepository.findClinicalHospitalIdsInOrganisationsOf(Set.of(lone.getId())))
            .thenReturn(Set.of(lone.getId()));

        assertThat(access.organisationHospitalIds()).containsExactly(lone.getId());
    }

    @Test
    @DisplayName("an ADMIN assignment at a pharmacy or a laboratory opens no organisation scope, and grants nothing")
    void aProviderFacilityOpensNothing() {
        Hospital pharmacy = hospital();
        signIn("ROLE_ADMIN");
        callerHolds(assignment("ROLE_ADMIN", pharmacy, true));
        // The scope query returns clinical hospitals only: for a pharmacy, none (not even itself).
        when(assignmentRepository.findClinicalHospitalIdsInOrganisationsOf(Set.of(pharmacy.getId())))
            .thenReturn(Set.of());

        assertThat(access.organisationHospitalIds()).isEmpty();
        Set<String> nurse = Set.of("NURSE");
        assertThatThrownBy(() -> access.requireMayGrant(nurse)).isInstanceOf(AccessDeniedException.class);
    }

    // ── granting ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"NURSE", "DOCTOR", "RECEPTIONIST", "ACCOUNTANT", "LAB_TECHNICIAN"})
    @DisplayName("grants a staff role at any hospital of its organisation")
    void grantsStaffRolesAcrossTheOrganisation(String role) {
        organisationAdminOfOne();

        assertThat(grant(role, hospitalA)).isEqualTo(hospitalA.getId());
        assertThat(grant(role, hospitalB)).isEqualTo(hospitalB.getId());
    }

    @Test
    @DisplayName("never at another organisation's hospital")
    void neverInAnotherOrganisation() {
        organisationAdminOfOne();

        assertThatThrownBy(() -> grant("NURSE", hospitalC)).isInstanceOf(AccessDeniedException.class);
        // A global (hospital-less) grant is the super-admin's only.
        UserAccountAccess.Grant staffGrant = access.requireMayGrant(Set.of("NURSE"));
        assertThatThrownBy(() -> staffGrant.requireAt(null)).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "ADMIN", "HOSPITAL_ADMIN", "PROVIDER_ADMIN"})
    @DisplayName("never an admin role: not a super-admin, not another organisation admin, not a hospital admin")
    void neverAnAdminRole(String role) {
        organisationAdminOfOne();

        Set<String> alone = Set.of(role);
        Set<String> besideAStaffRole = Set.of("NURSE", role);
        assertThatThrownBy(() -> access.requireMayGrant(alone)).isInstanceOf(AccessDeniedException.class);
        // Nor smuggled next to a staff role.
        assertThatThrownBy(() -> access.requireMayGrant(besideAStaffRole)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("never a patient: this role reads no patient record, so it registers none")
    void neverAPatient() {
        organisationAdminOfOne();

        Set<String> patient = Set.of("PATIENT");
        Set<String> patientBesideAStaffRole = Set.of("NURSE", "PATIENT");
        assertThatThrownBy(() -> access.requireMayGrant(patient)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.requireMayGrant(patientBesideAStaffRole))
            .isInstanceOf(AccessDeniedException.class);
    }

    // ── assignments ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("reads and changes staff assignments across its organisation, and none outside it")
    void assignmentScopeIsTheOrganisation() {
        organisationAdminOfOne();
        UserAccountAccess.AssignmentScope scope = access.assignmentScope();

        assertThat(scope.everywhere()).isFalse();
        assertThat(scope.mayChange(assignment("ROLE_NURSE", hospitalA, true))).isTrue();
        assertThat(scope.mayChange(assignment("ROLE_DOCTOR", hospitalB, false))).isTrue();
        assertThat(scope.covers(assignment("ROLE_NURSE", hospitalC, true))).isFalse();
        assertThat(scope.mayChange(assignment("ROLE_NURSE", hospitalC, true))).isFalse();
    }

    @Test
    @DisplayName("never reads a PATIENT row: across its organisation that would be a patient list")
    void neverCoversAPatientRow() {
        organisationAdminOfOne();
        UserAccountAccess.AssignmentScope scope = access.assignmentScope();

        assertThat(scope.covers(assignment("ROLE_PATIENT", hospitalA, true))).isFalse();
        assertThat(scope.covers(assignment("ROLE_PATIENT", hospitalB, true))).isFalse();
        assertThat(scope.mayChange(assignment("ROLE_PATIENT", hospitalB, true))).isFalse();
        // The hospital admin of A still reads the patient rows of A, and nothing at B.
        signIn("ROLE_HOSPITAL_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));
        UserAccountAccess.AssignmentScope hospitalAdmin = access.assignmentScope();
        assertThat(hospitalAdmin.covers(assignment("ROLE_PATIENT", hospitalA, true))).isTrue();
        assertThat(hospitalAdmin.covers(assignment("ROLE_NURSE", hospitalB, true))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_HOSPITAL_ADMIN"})
    @DisplayName("never changes an admin's assignment, its own included, even inside its organisation")
    void neverChangesAnAdminRow(String role) {
        organisationAdminOfOne();

        assertThat(access.assignmentScope().mayChange(assignment(role, hospitalB, true))).isFalse();
    }

    // ── accounts and directory ──────────────────────────────────────────────

    @Test
    @DisplayName("the directory lists its organisation's staff, without patients' contact details")
    void directoryIsTheOrganisation() {
        organisationAdminOfOne();
        UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

        assertThat(scope.everyone()).isFalse();
        assertThat(scope.hospitalIds()).containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
        assertThat(scope.seesPatientContacts()).isFalse();
    }

    @Test
    @DisplayName("also a hospital administrator: that role's patient-contact directory is not widened to the organisation")
    void hospitalAdministratorsDirectoryIsNotWidened() {
        signIn("ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true), assignment("ROLE_ADMIN", hospitalA, true));
        UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

        // Patients' contact details are read at A (QA D125), so B is not in this directory.
        assertThat(scope.seesPatientContacts()).isTrue();
        assertThat(scope.hospitalIds()).containsExactly(hospitalA.getId());
        // The staff grant itself still reaches B.
        assertThat(grant("NURSE", hospitalB)).isEqualTo(hospitalB.getId());
    }

    @Test
    @DisplayName("review of #1246: HOSPITAL_ADMIN at A and ADMIN at C (another organisation): A's patient contacts never reach C")
    void patientContactsAreDecidedPerHospital() {
        signIn("ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true), assignment("ROLE_ADMIN", hospitalC, true));
        when(assignmentRepository.findClinicalHospitalIdsInOrganisationsOf(Set.of(hospitalC.getId())))
            .thenReturn(Set.of(hospitalC.getId()));

        UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

        // The page has one flag: so the hospitals it lists are exactly those the flag is true for.
        assertThat(scope.seesPatientContacts()).isTrue();
        assertThat(scope.hospitalIds()).containsExactly(hospitalA.getId()).doesNotContain(hospitalC.getId());
    }

    @Test
    @DisplayName("review of #1246: an ADMIN alone, at one hospital or across its organisation, never gets the patient-contact directory")
    void anOrganisationAdminNeverGetsPatientContacts() {
        organisationAdminOfOne();

        UserAccountAccess.DirectoryScope scope = access.requireDirectoryAccess();

        assertThat(scope.seesPatientContacts()).isFalse();
        assertThat(scope.hospitalIds()).containsExactlyInAnyOrder(hospitalA.getId(), hospitalB.getId());
    }

    private static List<UserRoleHospitalAssignment> rows(UserRoleHospitalAssignment... rows) {
        return List.of(rows);
    }

    @Test
    @DisplayName("QA D4020: an organisation admin names, as a holder, whoever holds a non-patient assignment in its organisation")
    void staffOfTheCallersOrganisation() {
        organisationAdminOfOne();
        UserAccountAccess.HolderReach reach = access.holderReach();

        // A nurse at the sister hospital; a doctor whose invitation still waits for its code.
        assertThat(reach.mayName(rows(assignment("ROLE_NURSE", hospitalB, true)))).isTrue();
        assertThat(reach.mayName(rows(assignment("ROLE_DOCTOR", hospitalA, false)))).isTrue();
        // A patient of its own hospital (an organisation admin reads no patient); another
        // organisation's clinician; someone with no assignment.
        assertThat(reach.mayName(rows(assignment("ROLE_PATIENT", hospitalA, true)))).isFalse();
        assertThat(reach.isOwnPatient(rows(assignment("ROLE_PATIENT", hospitalA, true)))).isFalse();
        assertThat(reach.mayName(rows(assignment("ROLE_DOCTOR", hospitalC, true)))).isFalse();
        assertThat(reach.mayName(rows())).isFalse();
        // A clinician of another organisation who is ALSO a patient at A is still not its staff.
        assertThat(reach.mayName(rows(
            assignment("ROLE_DOCTOR", hospitalC, true), assignment("ROLE_PATIENT", hospitalA, true)))).isFalse();
    }

    @Test
    @DisplayName("QA D4020: a hospital admin names the staff of the hospitals it administers, never another hospital's")
    void aHospitalAdminNamesItsOwnStaffOnly() {
        signIn("ROLE_HOSPITAL_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospitalA, true));
        UserAccountAccess.HolderReach reach = access.holderReach();

        assertThat(reach.everywhere()).isFalse();
        assertThat(reach.mayName(rows(assignment("ROLE_NURSE", hospitalA, true)))).isTrue();
        // The sister hospital is the organisation admin's, not this hospital admin's.
        assertThat(reach.mayName(rows(assignment("ROLE_NURSE", hospitalB, true)))).isFalse();
        // Its own hospital's patient it may name; another hospital's patient it may not.
        assertThat(reach.mayName(rows(assignment("ROLE_PATIENT", hospitalA, true)))).isTrue();
        assertThat(reach.isOwnPatient(rows(assignment("ROLE_PATIENT", hospitalA, false)))).isTrue();
        assertThat(reach.mayName(rows(assignment("ROLE_PATIENT", hospitalB, true)))).isFalse();
        assertThat(reach.isOwnPatient(rows(assignment("ROLE_PATIENT", hospitalB, true)))).isFalse();
    }

    @Test
    @DisplayName("it administers no ACCOUNT: no password reset, edit or delete, even of its own organisation's staff")
    void administersNoAccount() {
        organisationAdminOfOne();
        User nurse = new User();
        nurse.setId(UUID.randomUUID());
        nurse.setUserRoles(new HashSet<>());
        when(assignmentRepository.findByUserId(nurse.getId())).thenReturn(List.of(assignment("ROLE_NURSE", hospitalA, true)));
        when(assignmentRepository.findByUserIdIn(any()))
            .thenReturn(List.of());

        assertThat(access.canAdminister(nurse)).isFalse();
        assertThat(access.canDelete(nurse)).isFalse();
        assertThat(access.canView(nurse)).isFalse();
        assertThat(access.manageableAmong(List.of(nurse))).isEmpty();
    }
}
