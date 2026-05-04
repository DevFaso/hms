package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditSource;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.FrontendAuditEvent;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.PermissionMatrixAuditEvent;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.superadmin.AggregatedAuditEventDTO;
import com.example.hms.payload.dto.superadmin.AggregatedAuditPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.FrontendAuditEventRepository;
import com.example.hms.repository.PermissionMatrixAuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperAdminAuditAggregationServiceImplTest {

    @Mock
    private AuditEventLogRepository auditEventLogRepository;

    @Mock
    private FrontendAuditEventRepository frontendAuditEventRepository;

    @Mock
    private PermissionMatrixAuditEventRepository permissionMatrixAuditEventRepository;

    @InjectMocks
    private SuperAdminAuditAggregationServiceImpl service;

    private LocalDateTime t0; // earliest
    private LocalDateTime t1;
    private LocalDateTime t2; // latest

    @BeforeEach
    void setUp() {
        t0 = LocalDateTime.of(2026, 5, 1, 10, 0);
        t1 = LocalDateTime.of(2026, 5, 1, 11, 0);
        t2 = LocalDateTime.of(2026, 5, 1, 12, 0);
    }

    @Test
    void searchAggregated_mergesAllSourcesByTimestampDescending() {
        AuditEventLog support = AuditEventLog.builder()
            .eventType(AuditEventType.LOGIN)
            .eventDescription("user logged in")
            .userName("alice")
            .status(AuditStatus.SUCCESS)
            .build();
        support.setId(UUID.randomUUID());
        support.setEventTimestamp(t1); // middle

        FrontendAuditEvent frontend = FrontendAuditEvent.builder()
            .eventType("PAGE_VIEW")
            .actor("bob")
            .occurredAt(t2) // latest — should be first
            .metadata("/dashboard")
            .build();
        frontend.setId(UUID.randomUUID());

        PermissionMatrixAuditEvent permission = PermissionMatrixAuditEvent.builder()
            .id(UUID.randomUUID())
            .action(PermissionMatrixAuditAction.SNAPSHOT_PUBLISHED)
            .description("matrix promoted")
            .initiatedBy("carol")
            .createdAt(t0.toInstant(ZoneOffset.UTC)) // earliest
            .build();

        when(auditEventLogRepository.findByDateRange(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(support), PageRequest.of(0, 20), 1L));
        when(frontendAuditEventRepository.findInDateRangeOrdered(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(frontend));
        when(frontendAuditEventRepository.countInDateRange(isNull(), isNull())).thenReturn(1L);
        when(permissionMatrixAuditEventRepository.findInDateRangeOrdered(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(permission));
        when(permissionMatrixAuditEventRepository.countInDateRange(isNull(), isNull())).thenReturn(1L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            Set.of(), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(3);
        assertThat(page.content().get(0).source()).isEqualTo(AuditSource.FRONTEND);   // t2
        assertThat(page.content().get(1).source()).isEqualTo(AuditSource.SUPPORT);    // t1
        assertThat(page.content().get(2).source()).isEqualTo(AuditSource.PERMISSION_MATRIX); // t0
        assertThat(page.totalElements()).isEqualTo(3L);
    }

    @Test
    void searchAggregated_filtersToRequestedSourcesOnly() {
        FrontendAuditEvent frontend = FrontendAuditEvent.builder()
            .eventType("PAGE_VIEW")
            .actor("bob")
            .occurredAt(t1)
            .build();
        frontend.setId(UUID.randomUUID());

        when(frontendAuditEventRepository.findInDateRangeOrdered(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(frontend));
        when(frontendAuditEventRepository.countInDateRange(isNull(), isNull())).thenReturn(1L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.FRONTEND), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).source()).isEqualTo(AuditSource.FRONTEND);
        verifyNoInteractions(auditEventLogRepository, permissionMatrixAuditEventRepository);
    }

    @Test
    void searchAggregated_nullSourcesTreatedAsAll() {
        when(auditEventLogRepository.findByDateRange(any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));
        when(frontendAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(List.of());
        when(frontendAuditEventRepository.countInDateRange(any(), any())).thenReturn(0L);
        when(permissionMatrixAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(List.of());
        when(permissionMatrixAuditEventRepository.countInDateRange(any(), any())).thenReturn(0L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            null, t0, t2, PageRequest.of(0, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        // All three sources were consulted.
        verify(auditEventLogRepository).findByDateRange(any(), any(), any(Pageable.class));
        verify(frontendAuditEventRepository).findInDateRangeOrdered(any(), any(), any(Pageable.class));
        verify(permissionMatrixAuditEventRepository).findInDateRangeOrdered(any(), any(), any(Pageable.class));
    }

    @Test
    void searchAggregated_paginatesMergedStream() {
        // Five FRONTEND rows, t1 .. t5 ascending — sort puts them DESC,
        // then page(1, 2) should return rows at indices 2 and 3 of the
        // sorted list (i.e. the 3rd and 4th most-recent).
        List<FrontendAuditEvent> rows = List.of(
            frontendAt("e1", t0),
            frontendAt("e2", t0.plusMinutes(1)),
            frontendAt("e3", t0.plusMinutes(2)),
            frontendAt("e4", t0.plusMinutes(3)),
            frontendAt("e5", t0.plusMinutes(4))
        );
        when(frontendAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(rows);
        when(frontendAuditEventRepository.countInDateRange(any(), any())).thenReturn(5L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.FRONTEND), null, null, PageRequest.of(1, 2));

        assertThat(page.pageNumber()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5L);
        assertThat(page.totalPages()).isEqualTo(3); // ceil(5/2)
        assertThat(page.content()).extracting(AggregatedAuditEventDTO::eventType)
            .containsExactly("e3", "e2"); // 3rd and 4th most-recent
    }

    @Test
    void searchAggregated_supportOnly_skipsOtherRepositories() {
        when(auditEventLogRepository.findByDateRange(any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0L));

        AggregatedAuditPageDTO page = service.searchAggregated(
            Set.of(AuditSource.SUPPORT), null, null, PageRequest.of(0, 20));

        assertThat(page.totalElements()).isZero();
        verify(frontendAuditEventRepository, never()).findInDateRangeOrdered(any(), any(), any(Pageable.class));
        verify(permissionMatrixAuditEventRepository, never()).findInDateRangeOrdered(any(), any(), any(Pageable.class));
    }

    private FrontendAuditEvent frontendAt(String eventType, LocalDateTime when) {
        FrontendAuditEvent e = FrontendAuditEvent.builder()
            .eventType(eventType)
            .occurredAt(when)
            .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    @Test
    void searchAggregated_capsTotalElementsAtPerSourceHardCap() {
        // Copilot review fix — when an underlying source reports 100 000
        // matching rows but the service can only fetch 5 000, the page
        // metadata must reflect what's actually retrievable, not the
        // raw COUNT(*). Otherwise pagination promises pages the service
        // can't deliver.
        when(frontendAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(List.of());
        when(frontendAuditEventRepository.countInDateRange(any(), any())).thenReturn(100_000L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.FRONTEND), null, null, PageRequest.of(0, 20));

        // PER_SOURCE_HARD_CAP is 5 000; totalElements should be capped.
        assertThat(page.totalElements()).isEqualTo(5_000L);
        assertThat(page.totalPages()).isEqualTo(250); // 5000 / 20
    }

    @Test
    void searchAggregated_pageSizeOverMaxIsClamped() {
        // Copilot review fix — pageSize is user-controlled; clamp before
        // any arithmetic so offset + pageSize cannot overflow int.
        when(frontendAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(List.of());
        when(frontendAuditEventRepository.countInDateRange(any(), any())).thenReturn(0L);

        // Request pageSize = Integer.MAX_VALUE; service must clamp to
        // MAX_PAGE_SIZE (= PER_SOURCE_HARD_CAP = 5 000) before computing
        // offset / perSourceLimit.
        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.FRONTEND), null, null,
            PageRequest.of(0, Integer.MAX_VALUE));

        assertThat(page.pageSize()).isEqualTo(5_000);
    }

    @Test
    void searchAggregated_offsetBeyondResults_returnsEmptyPage() {
        // Exercises the Math.min(offset, merged.size()) and
        // Math.min(offset + size, merged.size()) bounds when the requested
        // offset exceeds the merged stream size.
        FrontendAuditEvent only = frontendAt("only", t1);
        when(frontendAuditEventRepository.findInDateRangeOrdered(any(), any(), any(Pageable.class)))
            .thenReturn(List.of(only));
        when(frontendAuditEventRepository.countInDateRange(any(), any())).thenReturn(1L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.FRONTEND), null, null, PageRequest.of(5, 20));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void searchAggregated_supportRowWithAssignmentButNullHospital_hospitalIdIsNull() {
        // Exercises the (assignment != null && hospital == null) intermediate
        // branch in toDto(AuditEventLog).
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .hospital(null)
            .build();

        AuditEventLog row = AuditEventLog.builder()
            .eventType(AuditEventType.LOGIN)
            .eventDescription("hospital missing")
            .userName("alice")
            .status(AuditStatus.SUCCESS)
            .assignment(assignment)
            .build();
        row.setId(UUID.randomUUID());
        row.setEventTimestamp(t1);

        when(auditEventLogRepository.findByDateRange(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1L));

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.SUPPORT), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).hospitalId()).isNull();
        assertThat(page.content().get(0).organizationId()).isNull();
    }

    @Test
    void searchAggregated_supportRowWithHospitalButNullOrganization_organizationIdIsNull() {
        // Exercises the (hospital != null && organization == null) branch
        // in the second compound expression in toDto(AuditEventLog).
        Hospital hospital = Hospital.builder().organization(null).build();
        hospital.setId(UUID.randomUUID());

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .hospital(hospital)
            .build();

        AuditEventLog row = AuditEventLog.builder()
            .eventType(AuditEventType.LOGIN)
            .eventDescription("organization missing")
            .userName("alice")
            .status(AuditStatus.SUCCESS)
            .assignment(assignment)
            .build();
        row.setId(UUID.randomUUID());
        row.setEventTimestamp(t1);

        when(auditEventLogRepository.findByDateRange(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1L));

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.SUPPORT), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).hospitalId()).isEqualTo(hospital.getId());
        assertThat(page.content().get(0).organizationId()).isNull();
    }

    @Test
    void searchAggregated_supportRowWithFullAssignmentChain_populatesAllIds() {
        // Exercises the all-non-null branch in both compound expressions.
        Organization org = Organization.builder().build();
        org.setId(UUID.randomUUID());
        Hospital hospital = Hospital.builder().organization(org).build();
        hospital.setId(UUID.randomUUID());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .hospital(hospital)
            .build();

        AuditEventLog row = AuditEventLog.builder()
            .eventType(AuditEventType.LOGIN)
            .eventDescription("complete chain")
            .userName("alice")
            .status(AuditStatus.SUCCESS)
            .assignment(assignment)
            .build();
        row.setId(UUID.randomUUID());
        row.setEventTimestamp(t1);

        when(auditEventLogRepository.findByDateRange(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1L));

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.SUPPORT), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).hospitalId()).isEqualTo(hospital.getId());
        assertThat(page.content().get(0).organizationId()).isEqualTo(org.getId());
    }

    @Test
    void searchAggregated_supportRowWithNullAssignmentAndEnums_mapsNullsCleanly() {
        // Exercises every null-fallback branch in toDto(AuditEventLog):
        //   - assignment == null            → hospitalId / organizationId = null
        //   - eventType == null             → DTO eventType = null
        //   - status == null                → DTO status = null
        AuditEventLog row = AuditEventLog.builder()
            .eventDescription("bare minimum row")
            .userName("system")
            .build();
        row.setId(UUID.randomUUID());
        row.setEventTimestamp(t1);

        when(auditEventLogRepository.findByDateRange(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1L));

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.SUPPORT), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        AggregatedAuditEventDTO dto = page.content().get(0);
        assertThat(dto.source()).isEqualTo(AuditSource.SUPPORT);
        assertThat(dto.hospitalId()).isNull();
        assertThat(dto.organizationId()).isNull();
        assertThat(dto.eventType()).isNull();
        assertThat(dto.status()).isNull();
    }

    @Test
    void searchAggregated_permissionMatrixRowWithNullActionAndCreatedAt_mapsNullsCleanly() {
        // Exercises the two null-fallback branches in toDto(PermissionMatrixAuditEvent):
        //   - action == null     → DTO eventType = null
        //   - createdAt == null  → DTO timestamp = null
        PermissionMatrixAuditEvent row = PermissionMatrixAuditEvent.builder()
            .id(UUID.randomUUID())
            .description("bare minimum row")
            .initiatedBy("system")
            .build();

        when(permissionMatrixAuditEventRepository.findInDateRangeOrdered(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(List.of(row));
        when(permissionMatrixAuditEventRepository.countInDateRange(isNull(), isNull())).thenReturn(1L);

        AggregatedAuditPageDTO page = service.searchAggregated(
            EnumSet.of(AuditSource.PERMISSION_MATRIX), null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        AggregatedAuditEventDTO dto = page.content().get(0);
        assertThat(dto.source()).isEqualTo(AuditSource.PERMISSION_MATRIX);
        assertThat(dto.eventType()).isNull();
        assertThat(dto.timestamp()).isNull();
    }

    @Test
    void searchAggregated_permissionMatrixInstantConvertedFromLocalDateTimeBounds() {
        when(permissionMatrixAuditEventRepository.findInDateRangeOrdered(any(Instant.class), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(permissionMatrixAuditEventRepository.countInDateRange(any(Instant.class), any(Instant.class))).thenReturn(0L);

        service.searchAggregated(
            EnumSet.of(AuditSource.PERMISSION_MATRIX), t0, t2, PageRequest.of(0, 20));

        // The repo got Instants, not LocalDateTime — type conversion happened.
        verify(permissionMatrixAuditEventRepository)
            .findInDateRangeOrdered(any(Instant.class), any(Instant.class), any(Pageable.class));
        verify(permissionMatrixAuditEventRepository)
            .countInDateRange(any(Instant.class), any(Instant.class));
    }
}
