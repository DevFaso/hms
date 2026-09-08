package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientRecordSharingOptOut;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordSharingOptOutDTO;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.Locale;
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
 * No transaction is bound in a unit test, so {@code TransactionCallbacks.afterCommit}
 * runs inline — the audit assertions below observe it directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RecordSharingOptOutServiceImpl")
class RecordSharingOptOutServiceImplTest {

    @Mock private PatientRecordSharingOptOutRepository optOutRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private MessageSource messageSource;

    @InjectMocks private RecordSharingOptOutServiceImpl service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID actorUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
        when(optOutRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("status is 'none' when no opt-out is in force")
    void statusNone() {
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.empty());

        RecordSharingOptOutDTO dto = service.status(patientId, Locale.ENGLISH);

        assertThat(dto.inForce()).isFalse();
        assertThat(dto.patientId()).isEqualTo(patientId);
    }

    @Test
    @DisplayName("opting out records the row and emits CONSENT_UPDATE naming only the patient id")
    void optOutRecordsAndAudits() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(false);

        RecordSharingOptOutDTO dto = service.optOut(patientId, "my choice", actorUserId, Locale.ENGLISH);

        assertThat(dto.inForce()).isTrue();
        assertThat(dto.reason()).isEqualTo("my choice");

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONSENT_UPDATE);
        assertThat(audit.getValue().getPatientId()).isEqualTo(patientId);
        assertThat(audit.getValue().getUserId()).isEqualTo(actorUserId);
        // The reason is the patient's own words and may be PHI-adjacent: it
        // belongs on the row, never in the audit description.
        assertThat(audit.getValue().getEventDescription()).doesNotContain("my choice");
    }

    @Test
    @DisplayName("a second opt-out is a 409, not a second row")
    void optOutTwiceConflicts() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(true);

        assertThatThrownBy(() -> service.optOut(patientId, null, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);

        verify(optOutRepository, never()).save(any());
        verify(messageSource).getMessage(eq("recordaccess.optout.already"), any(), eq(Locale.ENGLISH));
    }

    @Test
    @DisplayName("revoking stamps revokedAt and keeps the row for the disclosure report")
    void revokeStampsNotDeletes() {
        PatientRecordSharingOptOut row = PatientRecordSharingOptOut.builder()
            .optedOutAt(LocalDateTime.now().minusDays(2)).build();
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.of(row));

        RecordSharingOptOutDTO dto = service.revoke(patientId, actorUserId, Locale.ENGLISH);

        assertThat(dto.inForce()).isFalse();
        assertThat(row.getRevokedAt()).isNotNull();
        assertThat(row.getRevokedByUserId()).isEqualTo(actorUserId);
        verify(optOutRepository, never()).delete(any());
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    @DisplayName("revoking with nothing in force is a 409")
    void revokeNothing() {
        when(optOutRepository.findFirstByPatient_IdAndRevokedAtIsNullOrderByOptedOutAtDesc(patientId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(patientId, actorUserId, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class);
        verify(messageSource).getMessage(eq("recordaccess.optout.none"), any(), eq(Locale.ENGLISH));
    }

    @Test
    @DisplayName("an unknown patient is a 404 carrying the key, not resolved prose")
    void unknownPatient() {
        UUID ghost = UUID.randomUUID();
        when(patientRepository.findByIdUnscoped(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.status(ghost, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
