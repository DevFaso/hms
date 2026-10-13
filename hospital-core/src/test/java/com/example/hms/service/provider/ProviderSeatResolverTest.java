package com.example.hms.service.provider;

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
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.support.UserAccountAccess;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Which provider facility a {@code /provider/**} request acts at, and as whom
 * (provider plan §3.1, §3.3): decided from the caller's LIVE assignments; the
 * seat pins the request; the admin rule is {@code UserAccountAccess}'s own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderSeatResolverTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    private ProviderSeatResolver resolver;
    private UserAccountAccess accountAccess;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital pharmacy = facility(FacilityType.PHARMACY);
    private final Hospital otherPharmacy = facility(FacilityType.PHARMACY);
    private final Hospital hospital = facility(FacilityType.HOSPITAL);

    @BeforeEach
    void setUp() {
        ActingScopeResolver scopeResolver = ActingScopeTestSupport.resolver();
        accountAccess = new UserAccountAccess(assignmentRepository, patientRepository, registrationRepository,
            staffRepository, new RoleValidator(assignmentRepository, scopeResolver));
        resolver = new ProviderSeatResolver(scopeResolver, assignmentRepository, accountAccess);
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("pinned at its pharmacy with a staff row there: a seat, not an admin")
    void pinnedPharmacist() {
        signIn("ROLE_PHARMACIST");
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PHARMACIST", pharmacy, true));

        Optional<ProviderSeat> seat = resolver.current();

        assertThat(seat).isPresent();
        assertThat(seat.get().facility()).isSameAs(pharmacy);
        assertThat(seat.get().admin()).isFalse();
        assertThat(resolver.currentAdmin()).isEmpty();
    }

    @Test
    @DisplayName("the admin bit is UserAccountAccess's rule: it agrees with the provider staff scope the batch changes use")
    void adminRuleAgreesWithTheAssignmentScope() {
        UserRoleHospitalAssignment staffRowHere = row("ROLE_PHARMACIST", pharmacy, true);
        // Each case: whether the PROVIDER_ADMIN row is live, then whether the token presents the role.
        boolean[][] cases = {{true, true}, {true, false}, {false, true}};
        for (boolean[] c : cases) {
            boolean liveRow = c[0];
            boolean presented = c[1];
            HospitalContextHolder.clear();
            signIn(presented ? "ROLE_PROVIDER_ADMIN" : "ROLE_PHARMACIST");
            ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
            callerHolds(row("ROLE_PHARMACIST", pharmacy, true), row("ROLE_PROVIDER_ADMIN", pharmacy, liveRow));

            boolean seatAdmin = resolver.current().map(ProviderSeat::admin).orElse(false);

            assertThat(seatAdmin).as("live=%s presented=%s", liveRow, presented)
                .isEqualTo(accountAccess.providerStaffScope().mayChange(staffRowHere))
                .isEqualTo(liveRow && presented);
        }
    }

    @Test
    @DisplayName("a hospital user: no seat")
    void hospitalUser() {
        signIn("ROLE_DOCTOR");
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_DOCTOR", hospital, true));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("pinned to the hospital where a provider user is a patient: no seat")
    void pinnedToPatientHospital() {
        signIn("ROLE_PHARMACIST", "ROLE_PATIENT");
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_PHARMACIST", pharmacy, true), row("ROLE_PATIENT", hospital, true));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("several facilities, none named, one provider facility: that one, and the request is PINNED there")
    void ambiguousWithOneProviderPinsTheRequest() {
        signIn("ROLE_PHARMACIST", "ROLE_PATIENT");
        ambiguous();
        callerHolds(row("ROLE_PHARMACIST", pharmacy, true), row("ROLE_PATIENT", hospital, true));

        assertThat(resolver.current()).map(ProviderSeat::facility).contains(pharmacy);
        // What the write audit reads in afterCompletion.
        assertThat(ActingScopeResolver.pinnedHospitalIdOrNull()).isEqualTo(pharmacy.getId());
    }

    @Test
    @DisplayName("two branches, none named: no seat, nothing pinned")
    void ambiguousWithTwoProviders() {
        signIn("ROLE_PHARMACIST");
        ambiguous();
        callerHolds(row("ROLE_PHARMACIST", pharmacy, true), row("ROLE_PHARMACIST", otherPharmacy, true));

        assertThat(resolver.current()).isEmpty();
        assertThat(ActingScopeResolver.scopeOf(HospitalContextHolder.getContextOrEmpty()))
            .isInstanceOf(ActingScope.Refused.class);
    }

    @Test
    @DisplayName("a PATIENT row at a provider is no seat")
    void patientRowIsNoSeat() {
        signIn("ROLE_PATIENT");
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PATIENT", pharmacy, true));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("a super-admin in global view, or no linked account: no seat")
    void noSeatWithoutAPin() {
        ActingScopeTestSupport.globalSuperAdmin(callerId);
        callerHolds(row("ROLE_SUPER_ADMIN", null, true));
        assertThat(resolver.current()).isEmpty();

        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(pharmacy.getId())
            .permittedHospitalIds(Set.of(pharmacy.getId())).build());
        assertThat(resolver.current()).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void signIn(String... authorities) {
        User account = new User();
        account.setId(callerId);
        account.setUsername("caller");
        account.setUserRoles(new HashSet<>());
        account.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        var details = new CustomUserDetails(account, AuthorityUtils.createAuthorityList(authorities));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            details, "n/a", AuthorityUtils.createAuthorityList(authorities)));
    }

    private void ambiguous() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .permittedHospitalIds(Set.of(pharmacy.getId(), hospital.getId(), otherPharmacy.getId()))
            .scopeRefusal(ActingScope.Reason.AMBIGUOUS)
            .build());
    }

    private void callerHolds(UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId))
            .thenReturn(Arrays.stream(rows).filter(r -> Boolean.TRUE.equals(r.getActive())).toList());
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }

    private static UserRoleHospitalAssignment row(String roleCode, Hospital at, boolean active) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleCode);
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setRole(role);
        row.setHospital(at);
        row.setActive(active);
        return row;
    }
}
