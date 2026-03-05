package com.example.hms.service;

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
}
