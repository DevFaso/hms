package com.example.hms.security.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HospitalContextHolderTest {

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    void whenContextSet_thenContextAvailableFromHolder() {
        UUID userId = UUID.randomUUID();
        HospitalContext context = HospitalContext.builder()
            .principalUserId(userId)
            .principalUsername("superadmin")
            .permittedHospitalIds(Set.of(UUID.randomUUID()))
            .superAdmin(true)
            .hospitalAdmin(false)
            .build();

        HospitalContextHolder.setContext(context);

        assertThat(HospitalContextHolder.getContext()).contains(context);
        assertThat(HospitalContextHolder.getContextOrEmpty()).isEqualTo(context);
    }

    @Test
    void whenContextCleared_thenHolderReturnsEmptyContext() {
        HospitalContextHolder.setContext(null);

        assertThat(HospitalContextHolder.getContext()).isEmpty();
        assertThat(HospitalContextHolder.getContextOrEmpty()).isNotNull();
        assertThat(HospitalContextHolder.getContextOrEmpty().getPrincipalUserId()).isNull();
    }
}
