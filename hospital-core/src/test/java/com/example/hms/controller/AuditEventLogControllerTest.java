package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventLogControllerTest {

    @Mock private AuditEventLogService auditService;
    @Mock private AuditEventLogRepository auditRepository;
    @Mock private AuditEventLogMapper auditMapper;

    @InjectMocks private AuditEventLogController controller;

    private Pageable pageable;
    private AuditEventLogResponseDTO responseDTO;
    private AuditEventLog entity;

    @BeforeEach
    void setUp() {
        pageable = PageRequest.of(0, 20);
        responseDTO = AuditEventLogResponseDTO.builder().build();
        entity = new AuditEventLog();
        entity.setId(UUID.randomUUID());
    }

    // ─── logEvent ────────────────────────────────────────────────

    @Test
    void logEventReturnsOk() {
        AuditEventRequestDTO requestDTO = new AuditEventRequestDTO();
        when(auditService.logEvent(requestDTO)).thenReturn(responseDTO);

        ResponseEntity<AuditEventLogResponseDTO> result = controller.logEvent(requestDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDTO);
        verify(auditService).logEvent(requestDTO);
    }

    // ─── getAllAuditLogs ─────────────────────────────────────────

    @Test
    void getAllAuditLogsReturnsPagedResults() {
        Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
        when(auditRepository.findAll(pageable)).thenReturn(page);
        when(auditMapper.toDto(entity)).thenReturn(responseDTO);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getAllAuditLogs(pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getContent()).hasSize(1);
        assertThat(result.getBody().getContent().get(0)).isEqualTo(responseDTO);
    }

    @Test
    void getAllAuditLogsReturnsEmptyPage() {
        Page<AuditEventLog> page = Page.empty(pageable);
        when(auditRepository.findAll(pageable)).thenReturn(page);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getAllAuditLogs(pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).isEmpty();
    }

    // ─── getLogsByUser ───────────────────────────────────────────

    @Test
    void getLogsByUserReturnsOk() {
        UUID userId = UUID.randomUUID();
        Page<AuditEventLogResponseDTO> dtoPage = new PageImpl<>(List.of(responseDTO));
        when(auditService.getAuditLogsByUser(userId, pageable)).thenReturn(dtoPage);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getLogsByUser(userId, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).hasSize(1);
    }

    // ─── getLogsByEventTypeAndStatus ─────────────────────────────

    @Nested
    class GetLogsByEventTypeAndStatus {

        @Test
        void withEventTypeOnly_statusNull() {
            Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
            when(auditRepository.findByEventType(AuditEventType.LOGIN, pageable)).thenReturn(page);
            when(auditMapper.toDto(entity)).thenReturn(responseDTO);

            ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                    controller.getLogsByEventTypeAndStatus("LOGIN", null, pageable);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getContent()).hasSize(1);
            verify(auditRepository).findByEventType(AuditEventType.LOGIN, pageable);
            verify(auditRepository, never()).findByEventTypeAndStatus(any(), any(), any());
        }

        @Test
        void withEventTypeOnly_statusBlank() {
            Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
            when(auditRepository.findByEventType(AuditEventType.LOGOUT, pageable)).thenReturn(page);
            when(auditMapper.toDto(entity)).thenReturn(responseDTO);

            ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                    controller.getLogsByEventTypeAndStatus("logout", "  ", pageable);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(auditRepository).findByEventType(AuditEventType.LOGOUT, pageable);
        }

        @Test
        void withEventTypeAndStatus() {
            Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
            when(auditRepository.findByEventTypeAndStatus(AuditEventType.LOGIN, AuditStatus.SUCCESS, pageable))
                    .thenReturn(page);
            when(auditMapper.toDto(entity)).thenReturn(responseDTO);

            ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                    controller.getLogsByEventTypeAndStatus("login", "success", pageable);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getContent()).hasSize(1);
            verify(auditRepository).findByEventTypeAndStatus(AuditEventType.LOGIN, AuditStatus.SUCCESS, pageable);
        }

        @Test
        void withEventTypeCaseInsensitive() {
            Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
            when(auditRepository.findByEventTypeAndStatus(AuditEventType.LOGIN, AuditStatus.FAILURE, pageable))
                    .thenReturn(page);
            when(auditMapper.toDto(entity)).thenReturn(responseDTO);

            ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                    controller.getLogsByEventTypeAndStatus("  Login  ", "  Failure  ", pageable);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(auditRepository).findByEventTypeAndStatus(AuditEventType.LOGIN, AuditStatus.FAILURE, pageable);
        }

        @Test
        void invalidEventTypeThrowsBadRequest() {
            assertThatThrownBy(() -> controller.getLogsByEventTypeAndStatus("INVALID_TYPE", null, pageable))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getReason()).contains("Invalid");
                    });
        }

        @Test
        void invalidStatusThrowsBadRequest() {
            assertThatThrownBy(() -> controller.getLogsByEventTypeAndStatus("LOGIN", "BAD_STATUS", pageable))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
    }

    // ─── getLogsByTarget ─────────────────────────────────────────

    @Test
    void getLogsByTargetReturnsOk() {
        String entityType = "PATIENT";
        String resourceId = UUID.randomUUID().toString();
        Page<AuditEventLog> page = new PageImpl<>(List.of(entity));
        when(auditRepository.findByEntityTypeIgnoreCaseAndResourceId(entityType, resourceId, pageable))
                .thenReturn(page);
        when(auditMapper.toDto(entity)).thenReturn(responseDTO);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                controller.getLogsByTarget(entityType, resourceId, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).hasSize(1);
    }

    @Test
    void getLogsByTargetReturnsEmptyPage() {
        Page<AuditEventLog> page = Page.empty(pageable);
        when(auditRepository.findByEntityTypeIgnoreCaseAndResourceId("USER", "abc", pageable))
                .thenReturn(page);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result =
                controller.getLogsByTarget("USER", "abc", pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).isEmpty();
    }
}
