package com.example.hms.security.tenant;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.context.HospitalContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider facility types ride on the live context, from the same
 * assignment read as the permitted set (provider plan section 3.3): the
 * confinement filter asks no query of its own.
 */
class LiveContextProviderTypesTest {

    private final UUID pharmacy = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();

    @Test
    @DisplayName("an active assignment at a pharmacy puts PHARMACY on the context, a hospital-bound PATIENT row puts nothing")
    void activeProviderAssignmentsOnly() {
        HospitalContext context = ActingScopeResolver.liveContext(UUID.randomUUID(), "pharm", List.of(
            new TenantRoleAssignment(pharmacy, null, "ROLE_PHARMACIST", "PHARMACIST", true, FacilityType.PHARMACY),
            new TenantRoleAssignment(hospital, null, "ROLE_PATIENT", "PATIENT", true, FacilityType.HOSPITAL)));

        assertThat(context.getProviderFacilityTypes()).containsExactly(FacilityType.PHARMACY);
        assertThat(context.getPermittedHospitalIds()).containsExactlyInAnyOrder(pharmacy, hospital);
        // Works at the pharmacy; is only a patient at the hospital.
        assertThat(context.getStaffHospitalIds()).containsExactly(pharmacy);
        // The kind of each of its facilities rides along, from the same read.
        assertThat(context.getHospitalFacilityTypes())
            .containsExactlyInAnyOrderEntriesOf(Map.of(pharmacy, FacilityType.PHARMACY, hospital, FacilityType.HOSPITAL));
    }

    @Test
    @DisplayName("a PATIENT row at a laboratory confines nobody: a patient-only user there is not a provider user")
    void patientRowAtAProviderConfinesNobody() {
        UUID laboratory = UUID.randomUUID();
        HospitalContext context = ActingScopeResolver.liveContext(UUID.randomUUID(), "pat", List.of(
            new TenantRoleAssignment(laboratory, null, "ROLE_PATIENT", "PATIENT", true, FacilityType.LABORATORY)));

        assertThat(context.getProviderFacilityTypes()).isEmpty();
        assertThat(context.getStaffHospitalIds()).isEmpty();
        // The facility is still known for what it is.
        assertThat(context.getHospitalFacilityTypes()).containsExactlyEntriesOf(
            Map.of(laboratory, FacilityType.LABORATORY));
    }

    @Test
    @DisplayName("an assignment at a facility cannot be built without its type (no fail-open default)")
    void facilityRowNeedsItsType() {
        assertThatThrownBy(() ->
                new TenantRoleAssignment(pharmacy, null, "ROLE_PHARMACIST", "PHARMACIST", true, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() ->
                new TenantRoleAssignment(null, null, "ROLE_SUPER_ADMIN", "SUPER_ADMIN", true, null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an inactive provider assignment, a hospital user and a global row confine nobody")
    void nothingElseConfines() {
        HospitalContext inactive = ActingScopeResolver.liveContext(UUID.randomUUID(), "former", List.of(
            new TenantRoleAssignment(pharmacy, null, "ROLE_PHARMACIST", "PHARMACIST", false, FacilityType.PHARMACY)));
        HospitalContext doctor = ActingScopeResolver.liveContext(UUID.randomUUID(), "doc", List.of(
            new TenantRoleAssignment(hospital, null, "ROLE_DOCTOR", "DOCTOR", true, FacilityType.HOSPITAL),
            new TenantRoleAssignment(null, null, "ROLE_PATIENT", "PATIENT", true, null)));

        assertThat(inactive.getProviderFacilityTypes()).isEmpty();
        assertThat(doctor.getProviderFacilityTypes()).isEmpty();
        assertThat(inactive.getHospitalFacilityTypes()).isEmpty();
        assertThat(doctor.getHospitalFacilityTypes()).containsExactlyEntriesOf(Map.of(hospital, FacilityType.HOSPITAL));
    }
}
