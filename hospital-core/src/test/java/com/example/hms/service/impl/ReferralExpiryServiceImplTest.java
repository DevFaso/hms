package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.service.ReferralEventRecorder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferralExpiryServiceImpl}.
 *
 * <p>The repository query already filters by status so the service mainly has
 * to translate the grace period into a cutoff and apply the entity guard. The
 * race-condition skip path is covered explicitly because it is the silent
 * branch — without a test, it would never be exercised in CI.
 *
 * <p>Time is supplied via a fixed {@link Clock} so the cutoff assertions are
 * deterministic — earlier versions used {@code LocalDateTime.now()} with
 * {@code ±1s} bounds, which was prone to flake under slow CI hosts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralExpiryServiceImpl")
class ReferralExpiryServiceImplTest {

    private static final Instant FIXED_NOW =
        LocalDateTime.of(2026, 5, 1, 12, 0, 0).toInstant(ZoneOffset.UTC);
    private static final LocalDateTime NOW_AS_LOCAL =
        LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
    private static final String SYSTEM_SOURCE = "scheduler";

    @Mock private GeneralReferralRepository referralRepository;
    @Mock private ReferralEventRecorder eventRecorder;

    private ReferralExpiryServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new ReferralExpiryServiceImpl(referralRepository, eventRecorder, fixed);
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
    }

    @Test
    void allEligibleReferralsAreExpired() {
        GeneralReferral a = newReferralIn(ReferralStatus.SUBMITTED);
        GeneralReferral b = newReferralIn(ReferralStatus.ACKNOWLEDGED);
        GeneralReferral c = newReferralIn(ReferralStatus.SCHEDULED);
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of(a, b, c));

        int result = service.expireOverdueReferrals(Duration.ofHours(1));

        assertThat(result).isEqualTo(3);
        assertThat(a.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        assertThat(b.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        assertThat(c.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        // Reason is the same audit-friendly sentinel for every sweep entry
        assertThat(a.getCancellationReason()).isNotBlank();
        assertThat(a.getCancellationReason()).isEqualTo(b.getCancellationReason());
        // One SYSTEM-actor audit row per expired referral, source=scheduler
        verify(eventRecorder, times(3)).recordSystemEvent(
            any(GeneralReferral.class),
            eq(ReferralEventType.EXPIRE),
            any(ReferralStatus.class),
            eq(SYSTEM_SOURCE),
            any());
    }

    @Test
    void raceConditionSkippedAndCountsOnlySuccessful() {
        // A referral that has already moved past SCHEDULED between the SELECT and the loop
        // (status flipped by an admin in another transaction) must NOT crash the sweep.
        GeneralReferral racy = newReferralIn(ReferralStatus.IN_PROGRESS);
        GeneralReferral healthy = newReferralIn(ReferralStatus.SUBMITTED);
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of(racy, healthy));

        int result = service.expireOverdueReferrals(Duration.ZERO);

        assertThat(result).isEqualTo(1);
        assertThat(racy.getStatus()).isEqualTo(ReferralStatus.IN_PROGRESS);
        assertThat(healthy.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        // Audit row only for the successful expiry — racy referral's failed transition
        // must NOT leak into the audit trail.
        verify(eventRecorder, times(1)).recordSystemEvent(
            any(GeneralReferral.class),
            eq(ReferralEventType.EXPIRE),
            any(ReferralStatus.class),
            eq(SYSTEM_SOURCE),
            any());
    }

    @Test
    void noEventEmittedWhenNothingExpires() {
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        service.expireOverdueReferrals(Duration.ZERO);

        verify(eventRecorder, never()).recordSystemEvent(
            any(), any(), any(), any(), any());
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

        int result = service.expireOverdueReferralsForHospital(Duration.ofHours(2), hospitalId);

        assertThat(result).isEqualTo(1);
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        // Critically: the unscoped query must NOT have been called.
        verify(referralRepository, never()).findExpirableReferrals(any());
        verify(referralRepository).findExpirableReferralsByHospital(eq(hospitalId),
            eq(NOW_AS_LOCAL.minusHours(2)));
        verify(eventRecorder).recordSystemEvent(
            any(GeneralReferral.class),
            eq(ReferralEventType.EXPIRE),
            any(ReferralStatus.class),
            eq(SYSTEM_SOURCE),
            any());
    }

    @Test
    void hospitalScopedSweepRequiresHospitalId() {
        assertThatThrownBy(() -> service.expireOverdueReferralsForHospital(Duration.ZERO, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("hospitalId");
    }
}
