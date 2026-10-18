package com.example.hms.controller;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventLogControllerTest {

    @Mock private AuditEventLogService auditService;
    @Mock private AuditEventLogRepository auditRepository;
    @Mock private AuditEventLogMapper auditMapper;
    @Mock private com.example.hms.controller.support.ControllerAuthUtils authUtils;
    @Mock private org.springframework.security.core.Authentication auth;
    @Mock private ActingScopeResolver scopeResolver;

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
        // The pre-D25 tests read as a super-admin in global view: the platform log.
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        org.mockito.Mockito.lenient().when(scopeResolver.current()).thenReturn(new ActingScope.Global(UUID.randomUUID()));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    // ─── D25: a hospital-scoped reader never reads the platform log ─────

    @Nested
    class HospitalScopedReader {

        private final UUID hospitalA = UUID.randomUUID();

        @BeforeEach
        void hospitalAdminOfA() {
            HospitalContextHolder.clear();
            HospitalContextHolder.setContext(HospitalContext.builder()
                .hospitalAdmin(true)
                .activeHospitalId(hospitalA)
                .permittedHospitalIds(Set.of(hospitalA))
                .staffHospitalIds(Set.of(hospitalA))
                .build());
            org.mockito.Mockito.lenient().when(scopeResolver.current())
                .thenReturn(new ActingScope.Pinned(hospitalA, ActingScope.Source.SOLE_ASSIGNMENT));
        }

        @Test
        @SuppressWarnings("unchecked")
        void listIsAnchoredAtTheCallersHospitalsNotThePlatformLog() {
            when(auditRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity)));
            when(auditMapper.toDto(entity)).thenReturn(responseDTO);

            ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getAllAuditLogs(null, null, pageable);

            assertThat(result.getBody().getContent()).containsExactly(responseDTO);
            verify(auditRepository, never()).findAll(any(Pageable.class));
            verify(auditService, never()).getAuditLogsByDateRange(any(), any(), any());
        }

        @Test
        void anotherHospitalsLogIsRefused() {
            UUID hospitalB = UUID.randomUUID();
            assertThatThrownBy(() -> controller.getLogsByHospital(hospitalB, pageable))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            org.mockito.Mockito.verifyNoInteractions(auditService);
        }

        @Test
        void ownHospitalsLogIsServed() {
            when(auditService.getAuditLogsByHospital(hospitalA, pageable)).thenReturn(new PageImpl<>(List.of(responseDTO)));
            assertThat(controller.getLogsByHospital(hospitalA, pageable).getBody().getContent()).hasSize(1);
        }

        @Test
        void eventTypeSummaryCountsOnlyTheCallersHospitals() {
            List<Object[]> rows = new java.util.ArrayList<>();
            rows.add(new Object[] {AuditEventType.LOGIN, 3L});
            when(auditRepository.countByEventTypeForHospitals(Set.of(hospitalA))).thenReturn(rows);

            assertThat(controller.getEventTypesSummary().getBody()).hasSize(1);
            verify(auditRepository, never()).countByEventType();
        }

        @Test
        void aMultiHospitalReaderWhoNamedNoneReadsEveryHospitalTheyWorkAt() {
            UUID hospitalA2 = UUID.randomUUID();
            HospitalContextHolder.clear();
            HospitalContextHolder.setContext(HospitalContext.builder()
                .hospitalAdmin(true).staffHospitalIds(Set.of(hospitalA, hospitalA2)).build());
            when(scopeResolver.current()).thenReturn(new ActingScope.Refused(ActingScope.Reason.AMBIGUOUS));
            when(auditRepository.countByEventTypeForHospitals(Set.of(hospitalA, hospitalA2))).thenReturn(List.of());

            assertThat(controller.getEventTypesSummary().getBody()).isEmpty();
            verify(auditRepository).countByEventTypeForHospitals(Set.of(hospitalA, hospitalA2));
        }

        @Test
        void aCallerWithNoStaffHospitalReadsNothing() {
            HospitalContextHolder.clear();
            HospitalContextHolder.setContext(HospitalContext.builder().hospitalAdmin(true).build());
            when(scopeResolver.current()).thenReturn(new ActingScope.Refused(ActingScope.Reason.NO_HOSPITAL));

            assertThat(controller.getEventTypesSummary().getBody()).isEmpty();
            verify(auditRepository, never()).countByEventType();
        }
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

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getAllAuditLogs(null, null, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getContent()).hasSize(1);
        assertThat(result.getBody().getContent().get(0)).isEqualTo(responseDTO);
    }

    @Test
    void getAllAuditLogsReturnsEmptyPage() {
        Page<AuditEventLog> page = Page.empty(pageable);
        when(auditRepository.findAll(pageable)).thenReturn(page);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getAllAuditLogs(null, null, pageable);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).isEmpty();
    }

    // ─── getLogsByUser ───────────────────────────────────────────

    @Test
    void getLogsByUserReturnsOk() {
        UUID userId = UUID.randomUUID();
        Page<AuditEventLogResponseDTO> dtoPage = new PageImpl<>(List.of(responseDTO));
        when(auditService.getAuditLogsByUser(userId, pageable)).thenReturn(dtoPage);
        when(authUtils.hasAuthority(auth, "ROLE_SUPER_ADMIN")).thenReturn(true);

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getLogsByUser(userId, pageable, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().getContent()).hasSize(1);
    }

    /** The profile's Activity tab: a patient reads their own activity without an audit role. */
    @Test
    void getLogsByUserLetsAnyoneReadTheirOwnActivity() {
        UUID userId = UUID.randomUUID();
        when(authUtils.resolveUserId(auth)).thenReturn(java.util.Optional.of(userId));
        when(auditService.getAuditLogsByUser(userId, pageable)).thenReturn(new PageImpl<>(List.of(responseDTO)));

        ResponseEntity<Page<AuditEventLogResponseDTO>> result = controller.getLogsByUser(userId, pageable, auth);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getLogsByUserRefusesAnotherUsersActivityWithoutAnAuditRole() {
        UUID other = UUID.randomUUID();
        when(authUtils.resolveUserId(auth)).thenReturn(java.util.Optional.of(UUID.randomUUID()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.getLogsByUser(other, pageable, auth))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(auditService);
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
