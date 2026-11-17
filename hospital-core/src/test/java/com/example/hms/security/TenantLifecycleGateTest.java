package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TenantLifecycleGate}: the semantics the password filter always had
 * (design §3.6) — ANY permitted hospital or organisation blocked refuses the
 * request, a verified super-admin passes — now shared with the Keycloak filter.
 */
class TenantLifecycleGateTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    private final OrganizationLifecycleStatusService organizations = mock(OrganizationLifecycleStatusService.class);
    private final HospitalLifecycleStatusService hospitals = mock(HospitalLifecycleStatusService.class);
    private final TenantLifecycleGate gate = new TenantLifecycleGate(organizations, hospitals);

    @Test
    @DisplayName("one suspended hospital among several blocks the caller, even acting at another")
    void anyBlockedHospitalBlocks() {
        when(hospitals.getBlockedHospitalIds()).thenReturn(Set.of(B));
        when(organizations.getBlockedOrganizationIds()).thenReturn(Set.of());
        HospitalContext actingAtA = HospitalContext.builder()
            .permittedHospitalIds(Set.of(A, B)).activeHospitalId(A).headerOverridden(true).build();

        assertThat(gate.isBlocked(actingAtA)).isTrue();
    }

    @Test
    @DisplayName("a blocked organisation blocks its hospitals' staff")
    void blockedOrganisationBlocks() {
        when(organizations.getBlockedOrganizationIds()).thenReturn(Set.of(ORG));
        HospitalContext context = HospitalContext.builder()
            .permittedHospitalIds(Set.of(A)).permittedOrganizationIds(Set.of(ORG)).build();

        assertThat(gate.isBlocked(context)).isTrue();
    }

    @Test
    @DisplayName("nothing blocked, no tenant at all, or a verified super-admin: not blocked")
    void notBlocked() {
        when(hospitals.getBlockedHospitalIds()).thenReturn(Set.of());
        when(organizations.getBlockedOrganizationIds()).thenReturn(Set.of());
        assertThat(gate.isBlocked(HospitalContext.builder().permittedHospitalIds(Set.of(A)).build())).isFalse();
        assertThat(gate.isBlocked(HospitalContext.empty())).isFalse();

        when(hospitals.getBlockedHospitalIds()).thenReturn(Set.of(A));
        assertThat(gate.isBlocked(HospitalContext.builder().superAdmin(true).permittedHospitalIds(Set.of(A)).build()))
            .isFalse();
        assertThat(gate.isBlocked(HospitalContext.builder().activeHospitalId(A).build()))
            .as("a hand-built context naming only the acting hospital").isTrue();
    }
}
