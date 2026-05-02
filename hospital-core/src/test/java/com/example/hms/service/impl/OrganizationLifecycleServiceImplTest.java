package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO;
import com.example.hms.payload.dto.superadmin.TenantLifecycleResponseDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrganizationLifecycleServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private AuditEventLogService auditEventLogService;

    @Mock
    private OrganizationLifecycleStatusService lifecycleStatusService;

    @InjectMocks
    private OrganizationLifecycleServiceImpl service;

    private UUID orgId;
    private UUID actorId;
    private Organization org;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        org = new Organization();
        org.setId(orgId);
        org.setName("Acme Health");
        org.setCode("ACME");
        org.setLifecycleState(OrganizationLifecycleState.ACTIVE);

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(actorId)
            .principalUsername("super.admin")
            .superAdmin(true)
            .permittedOrganizationIds(Set.of(orgId))
            .build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private TenantLifecycleActionRequestDTO withReason(String reason) {
        return TenantLifecycleActionRequestDTO.builder().reason(reason).build();
    }

    // ── suspend ────────────────────────────────────────────────────────

    @Test
    void suspendTransitionsActiveOrgAndEmitsAudit() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.suspend(orgId, withReason("non-payment"));

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.SUSPENDED);
        assertThat(result.getSuspendedBy()).isEqualTo(actorId);
        assertThat(result.getSuspensionReason()).isEqualTo("non-payment");
        assertThat(result.isCanRestore()).isTrue();
        assertThat(result.isCanSuspend()).isFalse();

        ArgumentCaptor<AuditEventRequestDTO> auditCap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCap.capture());
        assertThat(auditCap.getValue().getEventType()).isEqualTo(AuditEventType.TENANT_SUSPENDED);
        assertThat(auditCap.getValue().getResourceId()).isEqualTo(orgId.toString());
    }

    @Test
    void suspendRequiresAReason() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        TenantLifecycleActionRequestDTO blank = withReason("  ");
        assertThatThrownBy(() -> service.suspend(orgId, blank))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("reason is required");
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void suspendRejectsAlreadySuspendedOrg() {
        org.setLifecycleState(OrganizationLifecycleState.SUSPENDED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        TenantLifecycleActionRequestDTO req = withReason("ops");

        assertThatThrownBy(() -> service.suspend(orgId, req))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("Cannot suspend");
    }

    // ── restore ────────────────────────────────────────────────────────

    @Test
    void restoreSuspendedOrgReturnsToActive() {
        org.setLifecycleState(OrganizationLifecycleState.SUSPENDED);
        org.setSuspendedAt(Instant.now());
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.restore(orgId, null);

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.ACTIVE);
        // Snapshot fields are preserved for audit visibility
        assertThat(result.getSuspendedAt()).isNotNull();
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void restoreArchivedOrgReturnsToActive() {
        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.restore(orgId, null);

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.ACTIVE);
    }

    @Test
    void restoreRejectsActiveOrg() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> service.restore(orgId, null))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── archive ────────────────────────────────────────────────────────

    @Test
    void archiveActiveOrg() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.archive(orgId, withReason("off-boarded"));

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.ARCHIVED);
        assertThat(result.getArchiveReason()).isEqualTo("off-boarded");
        assertThat(result.isCanSchedulePurge()).isTrue();
    }

    @Test
    void archiveSuspendedOrgIsAllowed() {
        org.setLifecycleState(OrganizationLifecycleState.SUSPENDED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.archive(orgId, withReason("never returned"));

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.ARCHIVED);
    }

    @Test
    void archiveRejectsPurgedOrg() {
        org.setLifecycleState(OrganizationLifecycleState.PURGED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        TenantLifecycleActionRequestDTO req = withReason("x");

        assertThatThrownBy(() -> service.archive(orgId, req))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── schedulePurge ──────────────────────────────────────────────────

    @Test
    void schedulePurgeArchivedOrgUsesDefaultGraceWindow() {
        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        TenantLifecycleResponseDTO result = service.schedulePurge(orgId, withReason("retention policy"));

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PENDING_PURGE);
        assertThat(result.getPurgeScheduledFor()).isAfter(before.plus(29, ChronoUnit.DAYS));
        assertThat(result.isCanCancelPurge()).isTrue();
    }

    @Test
    void schedulePurgeHonoursExplicitFutureTime() {
        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant explicit = Instant.now().plus(7, ChronoUnit.DAYS);
        TenantLifecycleActionRequestDTO req = TenantLifecycleActionRequestDTO.builder()
            .reason("custom window").purgeScheduledFor(explicit).build();

        TenantLifecycleResponseDTO result = service.schedulePurge(orgId, req);

        assertThat(result.getPurgeScheduledFor()).isEqualTo(explicit);
    }

    @Test
    void schedulePurgeRejectsPastTime() {
        org.setLifecycleState(OrganizationLifecycleState.ARCHIVED);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        TenantLifecycleActionRequestDTO req = TenantLifecycleActionRequestDTO.builder()
            .reason("oops").purgeScheduledFor(Instant.now().minus(1, ChronoUnit.DAYS)).build();

        assertThatThrownBy(() -> service.schedulePurge(orgId, req))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("past");
    }

    @Test
    void schedulePurgeRejectsNonArchivedOrg() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        TenantLifecycleActionRequestDTO req = withReason("x");

        assertThatThrownBy(() -> service.schedulePurge(orgId, req))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── cancelPurge ────────────────────────────────────────────────────

    @Test
    void cancelPurgeReturnsToArchivedAndClearsScheduling() {
        org.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        org.setPurgeScheduledFor(Instant.now().plus(10, ChronoUnit.DAYS));
        org.setPurgeReason("scheduled");
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantLifecycleResponseDTO result = service.cancelPurge(orgId, null);

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.ARCHIVED);
        assertThat(result.getPurgeScheduledFor()).isNull();
        assertThat(result.getPurgeReason()).isNull();
        verify(auditEventLogService).logEvent(any());
    }

    @Test
    void cancelPurgeRejectsNonPendingOrg() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));

        assertThatThrownBy(() -> service.cancelPurge(orgId, null))
            .isInstanceOf(BusinessRuleException.class);
    }

    // ── load / general ─────────────────────────────────────────────────

    @Test
    void getLifecycleThrowsWhenOrgIsMissing() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLifecycle(orgId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void auditFailureDoesNotRollbackTransition() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditEventLogService.logEvent(any()))
            .thenThrow(new RuntimeException("audit log down"));

        TenantLifecycleResponseDTO result = service.suspend(orgId, withReason("ops"));

        assertThat(result.getLifecycleState()).isEqualTo(OrganizationLifecycleState.SUSPENDED);
        verify(organizationRepository, times(1)).save(any());
    }
}
