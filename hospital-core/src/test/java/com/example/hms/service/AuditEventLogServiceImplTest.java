package com.example.hms.service;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AuditEventLogServiceImpl} proving that audit logging is
 * best-effort and non-fatal — exceptions are swallowed, never propagated.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventLogServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditEventLogRepository auditRepository;
    @Mock private AuditEventLogMapper auditMapper;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;

    @InjectMocks
    private AuditEventLogServiceImpl auditService;

    // ─── Helpers ──────────────────────────────────────────────────────

    private AuditEventRequestDTO buildSystemBootstrapRequest() {
        return AuditEventRequestDTO.builder()
                .userId(null)
                .userName("SYSTEM")
                .assignmentId(null)
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("First system user bootstrap (Super Admin)")
                .details("Bootstrap user created: admin")
                .resourceId(UUID.randomUUID().toString())
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .ipAddress(null)
                .build();
    }

    private AuditEventRequestDTO buildNormalRequest(UUID userId) {
        return AuditEventRequestDTO.builder()
                .userId(userId)
                .assignmentId(null)
                .eventType(AuditEventType.ROLE_ASSIGNED)
                .eventDescription("Test event")
                .details("Some details")
                .resourceId(UUID.randomUUID().toString())
                .entityType("USER")
                .status(AuditStatus.SUCCESS)
                .build();
    }

    // ─── Non-fatal behaviour ──────────────────────────────────────────

    @Test
    @DisplayName("logEvent swallows repository exception and returns null")
    void logEvent_repositoryThrows_returnsNullAndDoesNotPropagate() {
        when(auditRepository.save(any())).thenThrow(new RuntimeException("DB connection lost"));

        AuditEventLogResponseDTO result = auditService.logEvent(buildSystemBootstrapRequest());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("logEvent never throws, even on unexpected runtime errors")
    void logEvent_neverThrows() {
        when(auditRepository.save(any())).thenThrow(new RuntimeException("Unexpected failure"));

        assertThatCode(() -> auditService.logEvent(buildSystemBootstrapRequest()))
                .doesNotThrowAnyException();
    }

    // ─── Null / missing user handling ─────────────────────────────────

    @Test
    @DisplayName("logEvent with null userId falls back to userName lookup gracefully")
    void logEvent_nullUserId_fallsBackToUserNameLookup() {
        AuditEventLog savedEntity = AuditEventLog.builder()
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("test")
                .build();
        when(userRepository.findByUsername("SYSTEM")).thenReturn(Optional.empty());
        when(auditRepository.save(any())).thenReturn(savedEntity);
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(buildSystemBootstrapRequest());

        // userId is null → no findById call; falls through to findByUsername("SYSTEM") which returns empty
        verify(userRepository, never()).findById(any());
        verify(userRepository).findByUsername("SYSTEM");
    }

    @Test
    @DisplayName("logEvent with unknown userId returns null user gracefully (no exception)")
    void logEvent_unknownUserId_returnsNullUser() {
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findById(unknownId)).thenReturn(Optional.empty());

        AuditEventLog savedEntity = AuditEventLog.builder()
                .eventType(AuditEventType.ROLE_ASSIGNED)
                .eventDescription("test")
                .build();
        when(auditRepository.save(any())).thenReturn(savedEntity);
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        AuditEventLogResponseDTO result = auditService.logEvent(buildNormalRequest(unknownId));

        // Should succeed — user simply resolves to null
        assertThat(result).isNotNull();
        verify(userRepository).findById(unknownId);
    }

    // ─── SYSTEM actor ─────────────────────────────────────────────────

    @Test
    @DisplayName("logEvent sets userName to SYSTEM for bootstrap flows")
    void logEvent_systemActor_setsUserNameToSystem() {
        AuditEventLog savedEntity = AuditEventLog.builder()
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("test")
                .userName("SYSTEM")
                .build();
        when(auditRepository.save(any())).thenReturn(savedEntity);
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(buildSystemBootstrapRequest());

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());

        AuditEventLog captured = captor.getValue();
        assertThat(captured.getUser()).isNull();
        assertThat(captured.getUserName()).isEqualTo("SYSTEM");
        assertThat(captured.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(captured.getActorLabel()).isEqualTo("SYSTEM");
    }

    // ─── Patient key (V141 / Tier 2 item 39) ──────────────────────────

    @Test
    @DisplayName("logEvent takes an explicit patientId over the entityType convention")
    void logEvent_explicitPatientId_wins() {
        // The whole point of the column: an emitter whose entityType is not
        // PATIENT can still put the row on the patient's disclosure list.
        // Break-the-glass and eligibility are exactly this shape, and before
        // V141 neither reached the patient.
        UUID patientId = UUID.randomUUID();
        when(auditRepository.save(any())).thenReturn(AuditEventLog.builder().build());
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(AuditEventType.BREAK_GLASS_ACCESS)
            .eventDescription("emergency access")
            .entityType("BREAK_GLASS_SESSION")
            .resourceId(UUID.randomUUID().toString())
            .patientId(patientId)
            .build());

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getPatientId()).isEqualTo(patientId);
    }

    @Test
    @DisplayName("logEvent derives the patient key from the entityType=PATIENT convention")
    void logEvent_derivesPatientIdFromConvention() {
        // Emitters that already followed the old convention keep working
        // untouched. Requiring every one of them to change would have been a
        // wider diff with more places to get it wrong, and the convention is
        // unambiguous where it is followed.
        UUID patientId = UUID.randomUUID();
        when(auditRepository.save(any())).thenReturn(AuditEventLog.builder().build());
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(AuditEventType.PATIENT_ACCESS)
            .eventDescription("chart opened")
            .entityType("PATIENT")
            .resourceId(patientId.toString())
            .build());

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getPatientId()).isEqualTo(patientId);
    }

    @Test
    @DisplayName("logEvent leaves the patient key null rather than failing on a non-UUID resource id")
    void logEvent_nonUuidResourceId_leavesPatientKeyNull() {
        // doLogEvent substitutes the literal "Unknown Resource" for a blank
        // resource id, so an unguarded UUID.fromString here would throw and
        // lose the audit row entirely. Losing the row is far worse than
        // losing the key.
        when(auditRepository.save(any())).thenReturn(AuditEventLog.builder().build());
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(AuditEventRequestDTO.builder()
            .eventType(AuditEventType.PATIENT_ACCESS)
            .eventDescription("chart opened")
            .entityType("PATIENT")
            .resourceId("not-a-uuid")
            .build());

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getPatientId()).isNull();
        // And crucially the row still persists. Before this was guarded, the
        // parse threw inside resolvePatientResourceName, logEvent swallowed
        // it, and the audit event vanished with no trace anywhere.
        assertThat(captor.getValue().getEventType()).isEqualTo(AuditEventType.PATIENT_ACCESS);
        assertThat(captor.getValue().getResourceName()).isEqualTo("not-a-uuid");
    }

    @Test
    @DisplayName("logEvent sets no patient key for events that concern no patient")
    void logEvent_nonPatientEvent_hasNoPatientKey() {
        when(auditRepository.save(any())).thenReturn(AuditEventLog.builder().build());
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());

        auditService.logEvent(buildSystemBootstrapRequest());

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());
        assertThat(captor.getValue().getPatientId()).isNull();
    }

    // ─── Successful persistence ───────────────────────────────────────

    @Test
    @DisplayName("logEvent persists and returns DTO on success")
    void logEvent_success_returnsMappedDto() {
        AuditEventLog savedEntity = AuditEventLog.builder()
                .eventType(AuditEventType.USER_BOOTSTRAP)
                .eventDescription("test")
                .build();
        AuditEventLogResponseDTO expectedDto = new AuditEventLogResponseDTO();

        when(auditRepository.save(any())).thenReturn(savedEntity);
        when(auditMapper.toDto(savedEntity)).thenReturn(expectedDto);

        AuditEventLogResponseDTO result = auditService.logEvent(buildSystemBootstrapRequest());

        assertThat(result).isSameAs(expectedDto);
        verify(auditRepository).save(any());
    }

    // --- date-range filter ---

    @Test
    @DisplayName("getAuditLogsByDateRange delegates to repository with correct params")
    void getAuditLogsByDateRange_delegatesToRepository() {
        LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2024, 12, 31, 23, 59);
        PageRequest pageable = PageRequest.of(0, 20);
        AuditEventLog log = AuditEventLog.builder()
            .eventType(AuditEventType.ROLE_ASSIGNED).eventDescription("test").build();
        Page<AuditEventLog> page = new PageImpl<>(List.of(log));
        AuditEventLogResponseDTO dto = new AuditEventLogResponseDTO();

        when(auditRepository.findByDateRange(from, to, pageable)).thenReturn(page);
        when(auditMapper.toDto(log)).thenReturn(dto);

        Page<AuditEventLogResponseDTO> result = auditService.getAuditLogsByDateRange(from, to, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0)).isSameAs(dto);
        verify(auditRepository).findByDateRange(from, to, pageable);
    }

    @Test
    @DisplayName("getAuditLogsByDateRange accepts null bounds (open-ended)")
    void getAuditLogsByDateRange_nullBounds_returnsAllLogs() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<AuditEventLog> empty = Page.empty();

        when(auditRepository.findByDateRange(null, null, pageable)).thenReturn(empty);

        Page<AuditEventLogResponseDTO> result = auditService.getAuditLogsByDateRange(null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(auditRepository).findByDateRange(null, null, pageable);
    }

    // --- hospital-scoped audit ---

    @Test
    @DisplayName("getAuditLogsByHospital delegates to repository with hospitalId")
    void getAuditLogsByHospital_delegatesToRepository() {
        UUID hospitalId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 20);
        Page<AuditEventLog> empty = Page.empty();

        when(auditRepository.findByAssignment_Hospital_IdOrderByEventTimestampDesc(hospitalId, pageable))
            .thenReturn(empty);

        Page<AuditEventLogResponseDTO> result = auditService.getAuditLogsByHospital(hospitalId, pageable);

        assertThat(result).isNotNull();
        verify(auditRepository).findByAssignment_Hospital_IdOrderByEventTimestampDesc(hospitalId, pageable);
    }
}
