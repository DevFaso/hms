package com.example.hms.service.impl;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferralExpiryServiceImpl}.
 *
 * <p>The repository query already filters by status so the service mainly has
 * to translate the grace period into a cutoff and apply the entity guard. The
 * race-condition skip path is covered explicitly because it is the silent
 * branch — without a test, it would never be exercised in CI.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralExpiryServiceImpl")
class ReferralExpiryServiceImplTest {

    @Mock private GeneralReferralRepository referralRepository;

    @InjectMocks private ReferralExpiryServiceImpl service;

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
    }

    @Test
    void cutoffEqualsNowMinusGraceWindow() {
        // A 6h grace window should query with a cutoff ≈ now() - 6h. Allow ±1s drift.
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        service.expireOverdueReferrals(Duration.ofHours(6));

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(referralRepository).findExpirableReferrals(cutoffCaptor.capture());

        LocalDateTime cutoff = cutoffCaptor.getValue();
        LocalDateTime expected = before.minusHours(6);
        assertThat(cutoff).isBetween(expected.minusSeconds(1), expected.plusSeconds(2));
    }

    @Test
    void nullGracePeriodTreatedAsZero() {
        when(referralRepository.findExpirableReferrals(any())).thenReturn(List.of());

        int result = service.expireOverdueReferrals(null);

        assertThat(result).isZero();
        verify(referralRepository).findExpirableReferrals(any(LocalDateTime.class));
    }
}
