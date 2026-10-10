package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.FacilityType;
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
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-5, AC-6 and T6: who may register an account at a provider
 * facility, through the real {@link UserAccountAccess}.
 *
 * <ul>
 *   <li>PROVIDER_ADMIN is an admin role: only a super-admin grants it, and a
 *       hospital admin neither grants it nor administers its holder.</li>
 *   <li>A provider admin registers staff at its own facility only, never in
 *       an admin role and never a PATIENT account: PROVIDER_ADMIN is on
 *       {@code PROVIDER_REGISTRAR_AUTHORITIES}, not on
 *       {@code USER_REGISTRAR_AUTHORITIES}.</li>
 * </ul>
 *
 * <p>Which staff role the facility takes (DOCTOR at a pharmacy is a 400) is
 * {@code FacilityAssignmentGuard}'s, covered by
 * {@code UserRoleHospitalAssignmentCompatibilityTest} and
 * {@code FacilityAssignmentGuardTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderRegistrarTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    private UserAccountAccess access;

    private final Hospital pharmacy = facility(FacilityType.PHARMACY);
    private final Hospital otherPharmacy = facility(FacilityType.PHARMACY);
    private final Hospital hospital = facility(FacilityType.HOSPITAL);
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

    @Test
    @DisplayName("the registrar lists are split: PROVIDER_ADMIN is never a patient registrar")
    void registrarListsAreSplit() {
        assertThat(SecurityConstants.authorities(SecurityConstants.USER_REGISTRAR_AUTHORITIES))
            .doesNotContain("ROLE_PROVIDER_ADMIN");
        assertThat(SecurityConstants.authorities(SecurityConstants.PROVIDER_REGISTRAR_AUTHORITIES))
            .containsExactly("ROLE_PROVIDER_ADMIN");
        assertThat(SecurityConstants.authorities(SecurityConstants.ADMIN_REGISTER_AUTHORITIES))
            .contains("ROLE_PROVIDER_ADMIN")
            .contains(SecurityConstants.authorities(SecurityConstants.USER_REGISTRAR_AUTHORITIES));
        assertThat(UserAccountAccess.REGISTRAR_ROLES).doesNotContain("PROVIDER_ADMIN");
        assertThat(UserAccountAccess.ADMIN_ROLES).contains("PROVIDER_ADMIN");
    }

    @Test
    @DisplayName("a provider admin registers a PHARMACIST at its own pharmacy")
    void registersStaffAtItsFacility() {
        signInAsProviderAdminOf(pharmacy);

        UserAccountAccess.Grant grant = access.requireMayGrant(Set.of("PHARMACIST"));

        assertThat(grant.requireAt(pharmacy.getId())).isEqualTo(pharmacy.getId());
    }

    @Test
    @DisplayName("but not at another facility, nor globally (403)")
    void notElsewhere() {
        signInAsProviderAdminOf(pharmacy);

        UserAccountAccess.Grant grant = access.requireMayGrant(Set.of("PHARMACIST"));

        assertRefusedAt(grant, otherPharmacy.getId());
        assertRefusedAt(grant, hospital.getId());
        assertRefusedAt(grant, null);
    }

    @Test
    @DisplayName("a provider admin grants no admin role, PROVIDER_ADMIN included (403)")
    void noAdminRole() {
        signInAsProviderAdminOf(pharmacy);

        assertGrantRefused(Set.of("PROVIDER_ADMIN"));
        assertGrantRefused(Set.of("HOSPITAL_ADMIN"));
    }

    @Test
    @DisplayName("a provider admin never registers a PATIENT account (403)")
    void neverAPatient() {
        signInAsProviderAdminOf(pharmacy);

        assertGrantRefused(Set.of("PATIENT"));
        assertGrantRefused(Set.of("PATIENT", "PHARMACIST"));
    }

    @Test
    @DisplayName("the authority alone grants nothing: the PROVIDER_ADMIN assignment must be live")
    void authorityWithoutAssignment() {
        signIn("ROLE_PROVIDER_ADMIN");
        callerHolds(assignment("ROLE_PROVIDER_ADMIN", pharmacy, false));

        assertGrantRefused(Set.of("PHARMACIST"));
    }

    @Test
    @DisplayName("a hospital admin cannot grant PROVIDER_ADMIN, even at its own hospital (an admin role)")
    void hospitalAdminCannotGrantProviderAdmin() {
        signIn("ROLE_HOSPITAL_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospital, true));

        assertGrantRefused(Set.of("PROVIDER_ADMIN"));
    }

    @Test
    @DisplayName("a hospital admin does not administer a provider admin's account")
    void hospitalAdminDoesNotAdministerAProviderAdmin() {
        signIn("ROLE_HOSPITAL_ADMIN");
        callerHolds(assignment("ROLE_HOSPITAL_ADMIN", hospital, true));
        User target = account(UUID.randomUUID());
        when(assignmentRepository.findByUserId(target.getId()))
            .thenReturn(List.of(assignment("ROLE_PROVIDER_ADMIN", hospital, false)));

        assertThat(access.canAdminister(target)).isFalse();
    }

    @Test
    @DisplayName("a verified super-admin grants PROVIDER_ADMIN at a provider facility")
    void superAdminGrantsProviderAdmin() {
        signIn("ROLE_SUPER_ADMIN");
        HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(callerId).superAdmin(true).build());

        assertThat(access.requireMayGrant(Set.of("PROVIDER_ADMIN")).requireAt(pharmacy.getId()))
            .isEqualTo(pharmacy.getId());
    }

    @Test
    @DisplayName("a provider admin may change its own facility's staff rows, never an admin's row or another facility's")
    void assignmentScopeCoversOwnFacilityStaff() {
        signInAsProviderAdminOf(pharmacy);

        UserAccountAccess.AssignmentScope scope = access.assignmentScope();

        assertThat(scope.everywhere()).isFalse();
        assertThat(scope.mayChange(assignment("ROLE_PHARMACIST", pharmacy, true))).isTrue();
        assertThat(scope.mayChange(assignment("ROLE_PROVIDER_ADMIN", pharmacy, true))).isFalse();
        assertThat(scope.covers(assignment("ROLE_PHARMACIST", otherPharmacy, true))).isFalse();
        assertThat(scope.covers(assignment("ROLE_DOCTOR", hospital, true))).isFalse();
    }

    @Test
    @DisplayName("the authority alone gives no assignment scope: the PROVIDER_ADMIN assignment must be live")
    void assignmentScopeNeedsTheLiveAssignment() {
        signIn("ROLE_PROVIDER_ADMIN");
        callerHolds(assignment("ROLE_PROVIDER_ADMIN", pharmacy, false));

        assertThat(access.assignmentScope().covers(assignment("ROLE_PHARMACIST", pharmacy, true))).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private void assertGrantRefused(Set<String> roles) {
        assertThatThrownBy(() -> access.requireMayGrant(roles)).isInstanceOf(AccessDeniedException.class);
    }

    private static void assertRefusedAt(UserAccountAccess.Grant grant, UUID facilityId) {
        assertThatThrownBy(() -> grant.requireAt(facilityId)).isInstanceOf(AccessDeniedException.class);
    }

    private void signInAsProviderAdminOf(Hospital facility) {
        signIn("ROLE_PROVIDER_ADMIN");
        callerHolds(assignment("ROLE_PROVIDER_ADMIN", facility, true));
    }

    private void signIn(String... authorities) {
        var details = new CustomUserDetails(account(callerId), AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .superAdmin(false)
            .build());
    }

    private void callerHolds(UserRoleHospitalAssignment... assignments) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(Arrays.stream(assignments).filter(a -> Boolean.TRUE.equals(a.getActive())).toList());
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }

    private static UserRoleHospitalAssignment assignment(String roleCode, Hospital hospital, boolean active) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setCode(roleCode);
        r.setName(roleCode);
        UserRoleHospitalAssignment a = new UserRoleHospitalAssignment();
        a.setId(UUID.randomUUID());
        a.setRole(r);
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
}
