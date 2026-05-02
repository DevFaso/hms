package com.example.hms.service.impl;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferralExpiryServiceImpl}.
 *
 * <p>The service is now an orchestrator: it picks the right repository
 * query (global vs hospital-scoped), computes the cutoff from a fixed
 * {@link Clock}, and delegates the actual UPDATE + audit row to
 * {@link ReferralExpiryPersistence#tryExpire}. Per-row optimistic-lock
 * skip semantics live in the persistence helper and are covered by
 * {@code ReferralExpiryPersistenceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralExpiryServiceImpl")
class ReferralExpiryServiceImplTest {

    private static final Instant FIXED_NOW =
        LocalDateTime.of(2026, 5, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDateTime NOW_AS_LOCAL =
        LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    @Mock private GeneralReferralRepository referralRepository;
    @Mock private ReferralExpiryPersistence expiryPersistence;

    private ReferralExpiryServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReferralExpiryServiceImpl(referralRepository, expiryPersistence, fixed);
    }

    private static GeneralReferral newReferralIn(ReferralStatus status) {
        GeneralReferral r = new GeneralReferral();
        r.setId(UUID.randomUUID());
        r.setStatus(status);
        r.setUrgency(ReferralUrgency.PRIORITY);
        r.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        r.setReferralType(ReferralType.CONSULTATION);
        r.setReferralReason("test");
        return r;
    }

    @Test
    void noEligibleReferralsReturnsZero() {
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        int result = service.expireOverdueReferrals(Duration.ZERO);

        assertThat(result).isZero();
        verify(expiryPersistence, never()).tryExpire(any(), anyString(), anyString());
    }

    @Test
    void everyEligibleReferralIsDelegatedToPersistence() {
        GeneralReferral a = newReferralIn(ReferralStatus.SUBMITTED);
        GeneralReferral b = newReferralIn(ReferralStatus.ACKNOWLEDGED);
        GeneralReferral c = newReferralIn(ReferralStatus.SCHEDULED);
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of(a, b, c));
        when(expiryPersistence.tryExpire(any(), anyString(), anyString())).thenReturn(true);

        int result = service.expireOverdueReferrals(Duration.ofHours(1));

        assertThat(result).isEqualTo(3);
        verify(expiryPersistence, times(3)).tryExpire(any(UUID.class), anyString(), eq("scheduler"));
    }

    @Test
    void countReflectsOnlyPersistenceSuccesses() {
        // The persistence helper returns false for both entity-guard and optimistic-lock
        // skip paths. The service must report only the count of successful commits.
        GeneralReferral skipped = newReferralIn(ReferralStatus.IN_PROGRESS);
        GeneralReferral healthy = newReferralIn(ReferralStatus.SUBMITTED);
        when(referralRepository.findExpirableReferrals(any()))
            .thenReturn(List.of(skipped, healthy));
        when(expiryPersistence.tryExpire(eq(skipped.getId()), anyString(), anyString()))
            .thenReturn(false);
        when(expiryPersistence.tryExpire(eq(healthy.getId()), anyString(), anyString()))
            .thenReturn(true);

        int result = service.expireOverdueReferrals(Duration.ZERO);

        assertThat(result).isEqualTo(1);
    }

    @Test
    void cutoffEqualsNowMinusGraceWindow() {
        // With a fixed Clock, the cutoff is exactly now() - grace — no drift, no flakes.
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        service.expireOverdueReferrals(Duration.ofHours(6));

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(referralRepository).findExpirableReferrals(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(NOW_AS_LOCAL.minusHours(6));
    }

    @Test
    void nullGracePeriodTreatedAsZero() {
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        int result = service.expireOverdueReferrals(null);

        assertThat(result).isZero();
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(referralRepository).findExpirableReferrals(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(NOW_AS_LOCAL);
    }

    @Test
    void negativeGracePeriodClampedToZero() {
        // A negative Duration would yield cutoff = now() + |grace|, expiring referrals
        // that are not actually overdue. The service must clamp to ZERO.
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        service.expireOverdueReferrals(Duration.ofHours(-3));

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(referralRepository).findExpirableReferrals(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isEqualTo(NOW_AS_LOCAL);
    }

    @Test
    void hospitalScopedSweepUsesByHospitalQuery() {
        UUID hospitalId = UUID.randomUUID();
        GeneralReferral r = newReferralIn(ReferralStatus.SUBMITTED);
        when(referralRepository.findExpirableReferralsByHospital(eq(hospitalId), any()))
            .thenReturn(List.of(r));
        when(expiryPersistence.tryExpire(any(), anyString(), anyString())).thenReturn(true);

        int result = service.expireOverdueReferralsForHospital(Duration.ofHours(2), hospitalId);

        assertThat(result).isEqualTo(1);
        // Critically: the unscoped query must NOT have been called.
        verify(referralRepository, never()).findExpirableReferrals(any());
        verify(referralRepository).findExpirableReferralsByHospital(eq(hospitalId),
            eq(NOW_AS_LOCAL.minusHours(2)));
    }

    @Test
    void hospitalScopedSweepRequiresHospitalId() {
        assertThatThrownBy(() -> service.expireOverdueReferralsForHospital(Duration.ZERO, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("hospitalId");
    }
}
