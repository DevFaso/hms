package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.repository.HospitalRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mirrors {@code OrganizationLifecycleStatusServiceImplTest} for the
 * hospital-level cache (MVP-c batch).
 */
@ExtendWith(MockitoExtension.class)
class HospitalLifecycleStatusServiceImplTest {

    @Mock private HospitalRepository hospitalRepository;
    @InjectMocks private HospitalLifecycleStatusServiceImpl service;

    private UUID hospitalA;
    private UUID hospitalB;

    @BeforeEach
    void setUp() {
        hospitalA = UUID.randomUUID();
        hospitalB = UUID.randomUUID();
    }

    @Test
    void firstReadHitsRepositoryAndReturnsBlockedSet() {
        when(hospitalRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(hospitalA, hospitalB));

        Set<UUID> result = service.getBlockedHospitalIds();

        assertThat(result).containsExactlyInAnyOrder(hospitalA, hospitalB);
        verify(hospitalRepository, times(1)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void subsequentReadsHitTheCacheAndDoNotQueryAgain() {
        when(hospitalRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(hospitalA));

        service.getBlockedHospitalIds();
        service.getBlockedHospitalIds();
        service.getBlockedHospitalIds();

        verify(hospitalRepository, times(1)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void invalidateForcesReloadOnNextRead() {
        when(hospitalRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(hospitalA))
            .thenReturn(List.of(hospitalA, hospitalB));

        Set<UUID> first = service.getBlockedHospitalIds();
        service.invalidate();
        Set<UUID> second = service.getBlockedHospitalIds();

        assertThat(first).containsExactly(hospitalA);
        assertThat(second).containsExactlyInAnyOrder(hospitalA, hospitalB);
        verify(hospitalRepository, times(2)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void isBlockedDelegatesToCachedSet() {
        when(hospitalRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(hospitalA));

        assertThat(service.isBlocked(hospitalA)).isTrue();
        assertThat(service.isBlocked(hospitalB)).isFalse();
        assertThat(service.isBlocked(null)).isFalse();
    }

    @Test
    void dbFailureFallsBackToEmptySetRatherThanLockingEveryHospitalOut() {
        when(hospitalRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenThrow(new RuntimeException("DB down"));

        Set<UUID> result = service.getBlockedHospitalIds();

        assertThat(result).isEmpty();
    }
}
