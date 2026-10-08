package com.example.hms.service.impl;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.MfaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider plan AC-4 / T22: the generic hospital-lifecycle restore never makes
 * an unverified provider ACTIVE. VERIFY is the only way; a verified provider
 * that was suspended comes back like any hospital.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalLifecycleProviderRestoreTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private HospitalLifecycleStatusService lifecycleStatusService;
    @Mock private MfaService mfaService;
    @Mock private ProviderVerificationRepository verificationRepository;

    private HospitalLifecycleServiceImpl service() {
        return new HospitalLifecycleServiceImpl(hospitalRepository, auditEventLogService, lifecycleStatusService,
            mfaService, Clock.fixed(Instant.parse("2026-10-08T09:00:00Z"), ZoneOffset.UTC), verificationRepository);
    }

    @ParameterizedTest(name = "a provider whose current verification is {0} is not restored (409)")
    @EnumSource(value = ProviderVerificationStatus.class, names = {"SUBMITTED", "REJECTED", "REVOKED"})
    void unverifiedProviderIsRefused(ProviderVerificationStatus status) {
        Hospital provider = suspended(FacilityType.PHARMACY);
        current(provider, status);

        assertThatThrownBy(() -> service().restore(provider.getId(), null)).isInstanceOf(ConflictException.class);
        assertThat(provider.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(provider.isActive()).isFalse();
        verify(hospitalRepository, never()).save(any());
    }

    @Test
    @DisplayName("a provider with no verification at all is refused too")
    void noVerification() {
        Hospital provider = suspended(FacilityType.LABORATORY);

        assertThatThrownBy(() -> service().restore(provider.getId(), null)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a VERIFIED provider that was suspended is restored")
    void verifiedProviderIsRestored() {
        Hospital provider = suspended(FacilityType.LABORATORY);
        current(provider, ProviderVerificationStatus.VERIFIED);

        service().restore(provider.getId(), null);

        assertThat(provider.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        assertThat(provider.isActive()).isTrue();
    }

    @Test
    @DisplayName("a hospital is restored as before, without any verification lookup")
    void hospitalUnchanged() {
        Hospital hospital = suspended(FacilityType.HOSPITAL);

        service().restore(hospital.getId(), null);

        assertThat(hospital.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        verify(verificationRepository, never()).findFirstByHospital_IdOrderByCreatedAtDesc(any());
    }

    private Hospital suspended(FacilityType type) {
        Hospital h = Hospital.builder()
            .name("F").code("F-" + UUID.randomUUID())
            .facilityType(type)
            .active(false)
            .lifecycleState(HospitalLifecycleState.SUSPENDED)
            .build();
        h.setId(UUID.randomUUID());
        when(hospitalRepository.findById(h.getId())).thenReturn(Optional.of(h));
        return h;
    }

    private void current(Hospital facility, ProviderVerificationStatus status) {
        ProviderVerification v = new ProviderVerification();
        v.setHospital(facility);
        v.setStatus(status);
        when(verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(facility.getId()))
            .thenReturn(Optional.of(v));
    }
}
