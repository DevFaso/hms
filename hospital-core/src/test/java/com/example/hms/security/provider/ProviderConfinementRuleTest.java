package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one confinement rule every caller uses (the filter, the STOMP
 * interceptor, the session bootstrap, the notification service), with its
 * linkage stated once.
 */
class ProviderConfinementRuleTest {

    private static HospitalContext context(UUID userId, Set<FacilityType> types, Set<String> roles, boolean superAdmin) {
        return HospitalContext.builder()
            .principalUserId(userId)
            .providerFacilityTypes(types)
            .assignedRoles(roles)
            .superAdmin(superAdmin)
            .build();
    }

    @Test
    @DisplayName("confined: a provider type and not a verified super-admin; nothing else")
    void isConfined() {
        UUID user = UUID.randomUUID();
        assertThat(ProviderConfinementPolicy.isConfined(
            context(user, Set.of(FacilityType.PHARMACY), Set.of("ROLE_PHARMACIST"), false))).isTrue();
        assertThat(ProviderConfinementPolicy.isConfined(
            context(user, Set.of(FacilityType.PHARMACY), Set.of("ROLE_SUPER_ADMIN"), true))).isFalse();
        assertThat(ProviderConfinementPolicy.isConfined(
            context(user, Set.of(), Set.of("ROLE_DOCTOR"), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isConfined(
            context(null, Set.of(), Set.of(), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isConfined(null)).isFalse();
    }

    @Test
    @DisplayName("linked and unconfined: a local account that is not confined; an unlinked caller is not")
    void isLinkedAndUnconfined() {
        UUID user = UUID.randomUUID();
        assertThat(ProviderConfinementPolicy.isLinkedAndUnconfined(
            context(user, Set.of(), Set.of("ROLE_NURSE"), false))).isTrue();
        assertThat(ProviderConfinementPolicy.isLinkedAndUnconfined(
            context(user, Set.of(), Set.of("ROLE_PATIENT"), false))).isTrue();
        assertThat(ProviderConfinementPolicy.isLinkedAndUnconfined(
            context(user, Set.of(FacilityType.LABORATORY), Set.of("ROLE_LAB_SCIENTIST"), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isLinkedAndUnconfined(
            context(null, Set.of(), Set.of("ROLE_NURSE"), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isLinkedAndUnconfined(null)).isFalse();
    }

    @Test
    @DisplayName("unconfined staff: linked, unconfined, and a role other than PATIENT (a verified super-admin included)")
    void isUnconfinedStaff() {
        UUID user = UUID.randomUUID();
        assertThat(ProviderConfinementPolicy.isUnconfinedStaff(
            context(user, Set.of(), Set.of("ROLE_DOCTOR"), false))).isTrue();
        assertThat(ProviderConfinementPolicy.isUnconfinedStaff(
            context(user, Set.of(FacilityType.PHARMACY), Set.of("ROLE_SUPER_ADMIN"), true))).isTrue();
        assertThat(ProviderConfinementPolicy.isUnconfinedStaff(
            context(user, Set.of(), Set.of("ROLE_PATIENT"), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isUnconfinedStaff(
            context(user, Set.of(FacilityType.PHARMACY), Set.of("ROLE_PHARMACIST"), false))).isFalse();
        assertThat(ProviderConfinementPolicy.isUnconfinedStaff(
            context(null, Set.of(), Set.of("ROLE_DOCTOR"), false))).isFalse();
    }
}
