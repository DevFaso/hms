package com.example.hms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdleSessionGateTest {

    private final IdleSessionTracker tracker = mock(IdleSessionTracker.class);

    private IdleSessionGate gate(String machineRolesCsv) {
        return new IdleSessionGate(tracker, machineRolesCsv);
    }

    private static List<GrantedAuthority> roles(String... names) {
        return java.util.Arrays.stream(names)
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
    }

    @Test
    @DisplayName("shouldReject is false when the tracker is disabled (feature flag off)")
    void shouldNotRejectWhenTrackerDisabled() {
        when(tracker.isEnabled()).thenReturn(false);

        assertThat(gate("").shouldReject(UUID.randomUUID(), roles("ROLE_DOCTOR"))).isFalse();
        verify(tracker, never()).isIdle(any());
    }

    @Test
    @DisplayName("shouldReject is false when the user holds a default machine role")
    void shouldNotRejectMachineRoles() {
        when(tracker.isEnabled()).thenReturn(true);

        for (String role : new String[]{
            "ROLE_FHIR_CLIENT", "ROLE_HL7_CLIENT", "ROLE_CDS_CLIENT",
            "ROLE_DHIS2_CLIENT", "ROLE_PARTNER_WEBHOOK"}) {
            assertThat(gate("").shouldReject(UUID.randomUUID(), roles(role)))
                .as("machine role %s must bypass the idle gate", role)
                .isFalse();
        }
        verify(tracker, never()).isIdle(any());
    }

    @Test
    @DisplayName("shouldReject delegates to tracker.isIdle for human users")
    void shouldRejectIdleHumans() {
        when(tracker.isEnabled()).thenReturn(true);
        UUID id = UUID.randomUUID();
        when(tracker.isIdle(id)).thenReturn(true);

        assertThat(gate("").shouldReject(id, roles("ROLE_DOCTOR"))).isTrue();

        when(tracker.isIdle(id)).thenReturn(false);
        assertThat(gate("").shouldReject(id, roles("ROLE_DOCTOR"))).isFalse();
    }

    @Test
    @DisplayName("shouldReject is false for null userId")
    void shouldNotRejectNullUser() {
        when(tracker.isEnabled()).thenReturn(true);

        assertThat(gate("").shouldReject(null, roles("ROLE_DOCTOR"))).isFalse();
        verify(tracker, never()).isIdle(any());
    }

    @Test
    @DisplayName("touchIfHuman skips machine-role users")
    void touchIfHumanSkipsMachines() {
        when(tracker.isEnabled()).thenReturn(true);
        UUID id = UUID.randomUUID();

        gate("").touchIfHuman(id, roles("ROLE_FHIR_CLIENT"));

        verify(tracker, never()).touch(any());
    }

    @Test
    @DisplayName("touchIfHuman touches when the user is human")
    void touchIfHumanTouchesHumans() {
        when(tracker.isEnabled()).thenReturn(true);
        UUID id = UUID.randomUUID();

        gate("").touchIfHuman(id, roles("ROLE_DOCTOR"));

        verify(tracker).touch(id);
    }

    @Test
    @DisplayName("touchIfHuman is a no-op when the tracker is disabled")
    void touchIfHumanRespectsDisabledTracker() {
        when(tracker.isEnabled()).thenReturn(false);

        gate("").touchIfHuman(UUID.randomUUID(), roles("ROLE_DOCTOR"));

        verify(tracker, never()).touch(any());
    }

    @Test
    @DisplayName("clear delegates to the tracker for non-null userId only")
    void clearDelegatesForNonNullUser() {
        when(tracker.isEnabled()).thenReturn(true);
        UUID id = UUID.randomUUID();

        gate("").clear(id);
        gate("").clear(null);

        verify(tracker).clear(id);
        verify(tracker, never()).clear(null);
    }

    @Test
    @DisplayName("custom machine-role CSV override replaces the default set")
    void customMachineRolesOverrideDefault() {
        when(tracker.isEnabled()).thenReturn(true);
        IdleSessionGate custom = gate("ROLE_CUSTOM_BOT, ROLE_INTEGRATION");

        // The new role bypasses the gate.
        assertThat(custom.shouldReject(UUID.randomUUID(), roles("ROLE_CUSTOM_BOT"))).isFalse();
        // The default machine role is no longer carved out.
        UUID id = UUID.randomUUID();
        when(tracker.isIdle(id)).thenReturn(true);
        assertThat(custom.shouldReject(id, roles("ROLE_FHIR_CLIENT"))).isTrue();
    }

    @Test
    @DisplayName("WWW-Authenticate challenge surfaces the idle_timeout reason")
    void wwwAuthenticateChallengeFormat() {
        assertThat(IdleSessionGate.wwwAuthenticateChallenge())
            .contains("Bearer", "error=\"invalid_token\"", "error_description=\"idle_timeout\"");
    }
}
