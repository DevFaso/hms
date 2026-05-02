package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.service.ReferralEventRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferralExpiryPersistence}.
 *
 * <p>This is the row-level commit boundary for the EXPIRED auto-sweep — it
 * owns the entity-guard try/catch and the optimistic-lock try/catch. Both
 * skip paths must return false AND emit no audit row. The success path
 * must persist the new status AND emit one SYSTEM:scheduler audit row.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralExpiryPersistence")
class ReferralExpiryPersistenceTest {

    private static final String REASON = "auto-expired";
    private static final String SOURCE = "scheduler";

    @Mock private GeneralReferralRepository referralRepository;
    @Mock private ReferralEventRecorder eventRecorder;

    @InjectMocks private ReferralExpiryPersistence persistence;

    private static GeneralReferral newReferralIn(UUID id, ReferralStatus status) {
        GeneralReferral r = new GeneralReferral();
        r.setId(id);
        r.setStatus(status);
        r.setUrgency(ReferralUrgency.PRIORITY);
        r.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        r.setReferralType(ReferralType.CONSULTATION);
        r.setReferralReason("test");
        return r;
    }

    @Test
    void successReturnsTrueAndEmitsAudit() {
        UUID id = UUID.randomUUID();
        GeneralReferral r = newReferralIn(id, ReferralStatus.SUBMITTED);
        when(referralRepository.findById(id)).thenReturn(Optional.of(r));
        when(referralRepository.saveAndFlush(any(GeneralReferral.class))).thenReturn(r);

        boolean result = persistence.tryExpire(id, REASON, SOURCE);

        assertThat(result).isTrue();
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.EXPIRED);
        verify(referralRepository).saveAndFlush(r);
        verify(eventRecorder).recordSystemEvent(
            eq(r),
            eq(ReferralEventType.EXPIRE),
            eq(ReferralStatus.SUBMITTED),
            eq(SOURCE),
            eq(REASON));
    }

    @Test
    void entityGuardRejectionReturnsFalseAndEmitsNoAudit() {
        // Pre-write race: the row's in-memory status moved to a state expire() rejects
        // (e.g. a clinician already started consulting). The entity guard throws and
        // we MUST skip without emitting an audit row.
        UUID id = UUID.randomUUID();
        GeneralReferral r = newReferralIn(id, ReferralStatus.IN_PROGRESS);
        when(referralRepository.findById(id)).thenReturn(Optional.of(r));

        boolean result = persistence.tryExpire(id, REASON, SOURCE);

        assertThat(result).isFalse();
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.IN_PROGRESS);
        verify(referralRepository, never()).saveAndFlush(any(GeneralReferral.class));
        verify(eventRecorder, never())
            .recordSystemEvent(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void optimisticLockingFailureReturnsFalseAndEmitsNoAudit() {
        // Lost-update race: the row was loaded with stale status SUBMITTED but a
        // concurrent transaction has already bumped @Version. saveAndFlush throws
        // OptimisticLockingFailureException; we must NOT emit an audit row for a
        // write that did not happen.
        UUID id = UUID.randomUUID();
        GeneralReferral r = newReferralIn(id, ReferralStatus.SUBMITTED);
        when(referralRepository.findById(id)).thenReturn(Optional.of(r));
        when(referralRepository.saveAndFlush(any(GeneralReferral.class)))
            .thenThrow(new OptimisticLockingFailureException("version mismatch"));

        boolean result = persistence.tryExpire(id, REASON, SOURCE);

        assertThat(result).isFalse();
        verify(eventRecorder, never())
            .recordSystemEvent(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void referralNotFoundThrows() {
        UUID id = UUID.randomUUID();
        when(referralRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> persistence.tryExpire(id, REASON, SOURCE))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(referralRepository, never()).saveAndFlush(any(GeneralReferral.class));
        verify(eventRecorder, never())
            .recordSystemEvent(any(), any(), any(), anyString(), anyString());
    }
}
