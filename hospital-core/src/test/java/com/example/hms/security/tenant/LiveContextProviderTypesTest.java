package com.example.hms.security.tenant;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    @DisplayName("an assignment at a facility cannot be built without its type (no fail-open default)")
    void facilityRowNeedsItsType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new TenantRoleAssignment(pharmacy, null, "ROLE_PHARMACIST", "PHARMACIST", true, null))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatCode(() ->
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
    }
}
