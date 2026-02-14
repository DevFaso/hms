package com.example.hms.service.support;

import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HospitalScopeUtilsTest {

    @Test
    void resolveScope_withPermittedAndActiveHospital() {
        UUID h1 = UUID.randomUUID();
        UUID h2 = UUID.randomUUID();
        UUID active = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of(h1, h2))
            .activeHospitalId(active)
            .build();

        LinkedHashSet<UUID> scope = HospitalScopeUtils.resolveScope(ctx);

        assertThat(scope).contains(h1, h2, active);
    }

    @Test
    void resolveScope_withNullActiveHospital() {
        UUID h1 = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of(h1))
            .activeHospitalId(null)
            .build();

        LinkedHashSet<UUID> scope = HospitalScopeUtils.resolveScope(ctx);

        assertThat(scope).containsExactly(h1);
    }

    @Test
    void resolveScope_emptyPermitted_onlyActiveReturned() {
        UUID active = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of())
            .activeHospitalId(active)
            .build();

        LinkedHashSet<UUID> scope = HospitalScopeUtils.resolveScope(ctx);

        assertThat(scope).containsExactly(active);
    }

    @Test
    void resolveScope_removesNullEntries() {
        // Create a set that might contain nulls through indirect means
        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of())
            .activeHospitalId(null)
            .build();

        LinkedHashSet<UUID> scope = HospitalScopeUtils.resolveScope(ctx);

        assertThat(scope).doesNotContainNull();
        assertThat(scope).isEmpty();
    }

    @Test
    void isHospitalAccessible_hospitalInScope_returnsTrue() {
        UUID h1 = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of(h1))
            .build();

        assertThat(HospitalScopeUtils.isHospitalAccessible(ctx, h1)).isTrue();
    }

    @Test
    void isHospitalAccessible_hospitalNotInScope_returnsFalse() {
        UUID h1 = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of(h1))
            .build();

        assertThat(HospitalScopeUtils.isHospitalAccessible(ctx, other)).isFalse();
    }

    @Test
    void isHospitalAccessible_nullHospitalId_returnsFalse() {
        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of(UUID.randomUUID()))
            .build();

        assertThat(HospitalScopeUtils.isHospitalAccessible(ctx, null)).isFalse();
    }

    @Test
    void isHospitalAccessible_activeHospital_returnsTrue() {
        UUID active = UUID.randomUUID();

        HospitalContext ctx = HospitalContext.builder()
            .permittedHospitalIds(Set.of())
            .activeHospitalId(active)
            .build();

        assertThat(HospitalScopeUtils.isHospitalAccessible(ctx, active)).isTrue();
    }
}
