package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
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

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Which provider facility a {@code /provider/**} request acts at, and as whom
 * (provider plan §3.1, §3.3): decided from the caller's LIVE assignments.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderSeatResolverTest {

    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private RoleValidator roleValidator;

    private ProviderSeatResolver resolver;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital pharmacy = facility(FacilityType.PHARMACY);
    private final Hospital otherPharmacy = facility(FacilityType.PHARMACY);
    private final Hospital hospital = facility(FacilityType.HOSPITAL);

    @BeforeEach
    void setUp() {
        resolver = new ProviderSeatResolver(ActingScopeTestSupport.resolver(), assignmentRepository, roleValidator);
        when(roleValidator.hasAnyAuthority(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("pinned at its pharmacy with a staff row there: a seat, not an admin")
    void pinnedPharmacist() {
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PHARMACIST", pharmacy));

        Optional<ProviderSeat> seat = resolver.current();

        assertThat(seat).isPresent();
        assertThat(seat.get().facility()).isSameAs(pharmacy);
        assertThat(seat.get().admin()).isFalse();
        assertThat(resolver.currentAdmin()).isEmpty();
    }

    @Test
    @DisplayName("a live PROVIDER_ADMIN row there, presented: an admin seat")
    void providerAdmin() {
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PROVIDER_ADMIN", pharmacy));

        assertThat(resolver.currentAdmin()).isPresent();
    }

    @Test
    @DisplayName("a live PROVIDER_ADMIN row the token does not present: no admin seat (the assignment service would refuse)")
    void providerAdminNotPresented() {
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PROVIDER_ADMIN", pharmacy));
        when(roleValidator.hasAnyAuthority(anyString())).thenReturn(false);

        assertThat(resolver.current()).isPresent();
        assertThat(resolver.currentAdmin()).isEmpty();
    }

    @Test
    @DisplayName("a hospital user: no seat")
    void hospitalUser() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_DOCTOR", hospital));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("pinned to the hospital where a provider user is a patient: no seat")
    void pinnedToPatientHospital() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_PHARMACIST", pharmacy), row("ROLE_PATIENT", hospital));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("several facilities, none named, one provider facility: that one")
    void ambiguousWithOneProvider() {
        ambiguous();
        callerHolds(row("ROLE_PHARMACIST", pharmacy), row("ROLE_PATIENT", hospital));

        assertThat(resolver.current()).map(ProviderSeat::facility).contains(pharmacy);
    }

    @Test
    @DisplayName("two branches, none named: no seat")
    void ambiguousWithTwoProviders() {
        ambiguous();
        callerHolds(row("ROLE_PHARMACIST", pharmacy), row("ROLE_PHARMACIST", otherPharmacy));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("a PATIENT row at a provider is no seat")
    void patientRowIsNoSeat() {
        ActingScopeTestSupport.actingAt(callerId, pharmacy.getId());
        callerHolds(row("ROLE_PATIENT", pharmacy));

        assertThat(resolver.current()).isEmpty();
    }

    @Test
    @DisplayName("a super-admin in global view, or no linked account: no seat")
    void noSeatWithoutAPin() {
        ActingScopeTestSupport.globalSuperAdmin(callerId);
        callerHolds(row("ROLE_SUPER_ADMIN", null));
        assertThat(resolver.current()).isEmpty();

        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(pharmacy.getId())
            .permittedHospitalIds(Set.of(pharmacy.getId())).build());
        assertThat(resolver.current()).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void ambiguous() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(callerId)
            .permittedHospitalIds(Set.of(pharmacy.getId(), hospital.getId(), otherPharmacy.getId()))
            .scopeRefusal(ActingScope.Reason.AMBIGUOUS)
            .build());
    }

    private void callerHolds(UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId)).thenReturn(Arrays.asList(rows));
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        return h;
    }

    private static UserRoleHospitalAssignment row(String roleCode, Hospital at) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleCode);
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setRole(role);
        row.setHospital(at);
        row.setActive(true);
        return row;
    }
}
