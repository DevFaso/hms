package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.repository.OrganizationRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationLifecycleStatusServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationLifecycleStatusServiceImpl service;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        orgA = UUID.randomUUID();
        orgB = UUID.randomUUID();
    }

    @Test
    void firstReadHitsTheRepositoryAndReturnsTheBlockedSet() {
        when(organizationRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(orgA, orgB));

        Set<UUID> result = service.getBlockedOrganizationIds();

        assertThat(result).containsExactlyInAnyOrder(orgA, orgB);
        verify(organizationRepository, times(1)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void subsequentReadsHitTheCacheAndDoNotQueryAgain() {
        when(organizationRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(orgA));

        service.getBlockedOrganizationIds();
        service.getBlockedOrganizationIds();
        service.getBlockedOrganizationIds();

        verify(organizationRepository, times(1)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void invalidateForcesAReloadOnNextRead() {
        when(organizationRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(orgA))
            .thenReturn(List.of(orgA, orgB));

        Set<UUID> first = service.getBlockedOrganizationIds();
        service.invalidate();
        Set<UUID> second = service.getBlockedOrganizationIds();

        assertThat(first).containsExactly(orgA);
        assertThat(second).containsExactlyInAnyOrder(orgA, orgB);
        verify(organizationRepository, times(2)).findIdsByLifecycleStateIn(anyCollection());
    }

    @Test
    void isBlockedDelegatesToTheCachedSet() {
        when(organizationRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenReturn(List.of(orgA));

        assertThat(service.isBlocked(orgA)).isTrue();
        assertThat(service.isBlocked(orgB)).isFalse();
        assertThat(service.isBlocked(null)).isFalse();
    }

    @Test
    void dbFailureFallsBackToEmptySetRatherThanLockingEveryoneOut() {
        when(organizationRepository.findIdsByLifecycleStateIn(anyCollection()))
            .thenThrow(new RuntimeException("DB down"));

        Set<UUID> result = service.getBlockedOrganizationIds();

        assertThat(result).isEmpty();
    }
}
