package com.example.hms.security.audit;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CrossTenantReadAudit} — the F3 follow-up from
 * {@code docs/super-admin-cross-tenant-design.md}.
 */
@ExtendWith(MockitoExtension.class)
class CrossTenantReadAuditTest {

    @Mock private AuditEventLogService auditEventLogService;

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordCrossTenantRead_emitsDataAccessEventForRealSuperAdmin() {
        UUID userId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .principalUserId(userId)
            .principalUsername("admin@example.com")
            .build());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin@example.com", "n/a"));

        new CrossTenantReadAudit(auditEventLogService)
            .recordCrossTenantRead("ENCOUNTER", "recent-encounters", 7);

        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        AuditEventRequestDTO emitted = captor.getValue();
        assertThat(emitted.getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
        assertThat(emitted.getEntityType()).isEqualTo("ENCOUNTER");
        assertThat(emitted.getResourceId()).isEqualTo("recent-encounters");
        assertThat(emitted.getResourceName()).isEqualTo("recent-encounters");
        assertThat(emitted.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(emitted.getUserId()).isEqualTo(userId);
        assertThat(emitted.getUserName()).isEqualTo("admin@example.com");
        assertThat(emitted.getEventDescription())
            .contains("recent-encounters")
            .contains("7 rows");
    }

    @Test
    void recordCrossTenantRead_skipsEmissionForNonSuperAdmin() {
        // F1/F3 invariant: only real super-admins (per JWT claim) trigger
        // cross-tenant-read audits. A regular user's hospital-scoped read
        // is already audited by its per-resource code; double-counting
        // here would skew anomaly detection.
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(false)
            .principalUserId(UUID.randomUUID())
            .build());

        new CrossTenantReadAudit(auditEventLogService)
            .recordCrossTenantRead("ENCOUNTER", "recent-encounters", 3);

        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void recordCrossTenantRead_skipsEmissionWhenNoContextSet() {
        // Defensive: empty HospitalContext → isSuperAdmin() == false → skip.
        new CrossTenantReadAudit(auditEventLogService)
            .recordCrossTenantRead("ENCOUNTER", "recent-encounters", 0);

        verify(auditEventLogService, never()).logEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordCrossTenantRead_swallowsExceptionsFromAuditService() {
        // The audit hook is best-effort; if logEvent throws (e.g. mocked
        // misconfiguration in tests, real DB outage in prod) the read
        // path that called us has already returned data — never propagate.
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .principalUserId(UUID.randomUUID())
            .build());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", "n/a"));
        when(auditEventLogService.logEvent(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("simulated audit DB outage"));
        CrossTenantReadAudit audit = new CrossTenantReadAudit(auditEventLogService);

        // Explicit assertion (Sonar S2699): the call must not propagate
        // any exception — the read path has already returned data and
        // an audit failure must never surface to the caller.
        assertThatCode(() ->
            audit.recordCrossTenantRead("ENCOUNTER", "recent-encounters", 1)
        ).doesNotThrowAnyException();

        // Belt-and-braces: the audit service WAS called (we exercised
        // the throwing branch, not the gating short-circuit).
        verify(auditEventLogService).logEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordCrossTenantRead_skipsASuperAdminWhoPinnedAHospital() {
        // Pinned: one hospital, like anyone else — no longer labelled
        // cross-tenant (design §3.6: the audit gates on the FINAL scope).
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true)
            .activeHospitalId(UUID.randomUUID())
            .headerOverridden(true)
            .build());
        new CrossTenantReadAudit(auditEventLogService).recordCrossTenantRead("ENCOUNTER", "recent", 3);
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    void recordRefusal_writesOneRejectedRowPerActorHospitalAndReasonPerHour() {
        CrossTenantReadAudit audit = new CrossTenantReadAudit(auditEventLogService);
        UUID actor = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        long now = 1_000_000L;

        audit.recordRefusal(actor, "dr.x", hospital,
            com.example.hms.security.tenant.ActingScope.Reason.NO_LONGER_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER, now);
        // The same stale chip on every request of the next hour: no new row.
        audit.recordRefusal(actor, "dr.x", hospital,
            com.example.hms.security.tenant.ActingScope.Reason.NO_LONGER_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER, now + 59 * 60_000L);
        // A probe of the same hospital is a different reason: its own row.
        audit.recordRefusal(actor, "dr.x", hospital,
            com.example.hms.security.tenant.ActingScope.Reason.NOT_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.REQUESTED, now + 60_000L);
        // An hour later the stale chip is recorded again.
        audit.recordRefusal(actor, "dr.x", hospital,
            com.example.hms.security.tenant.ActingScope.Reason.NO_LONGER_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER, now + 61 * 60_000L);

        ArgumentCaptor<AuditEventRequestDTO> rows = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, org.mockito.Mockito.times(3)).logEvent(rows.capture());
        AuditEventRequestDTO first = rows.getAllValues().get(0);
        assertThat(first.getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
        assertThat(first.getStatus()).isEqualTo(AuditStatus.REJECTED);
        assertThat(first.getUserId()).isEqualTo(actor);
        assertThat(first.getResourceId()).isEqualTo(hospital.toString());
        assertThat(first.getEventDescription()).contains("NO_LONGER_PERMITTED").doesNotContain("dr.x");
        assertThat(rows.getAllValues().get(1).getEventDescription()).contains("NOT_PERMITTED");
    }

    @Test
    void recordRefusal_keysAnUnlinkedPrincipalOnItsNameAndIgnoresNothingNamed() {
        CrossTenantReadAudit audit = new CrossTenantReadAudit(auditEventLogService);
        audit.recordRefusal(null, "kc.user", UUID.randomUUID(),
            com.example.hms.security.tenant.ActingScope.Reason.NOT_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER);
        audit.recordRefusal(null, null, UUID.randomUUID(),
            com.example.hms.security.tenant.ActingScope.Reason.NOT_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER);
        audit.recordRefusal(UUID.randomUUID(), "x", null,
            com.example.hms.security.tenant.ActingScope.Reason.NOT_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER);
        verify(auditEventLogService, org.mockito.Mockito.times(1)).logEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordGlobalViewRequest_writesIdsOnly_andSwallowsFailures() {
        CrossTenantReadAudit audit = new CrossTenantReadAudit(auditEventLogService);
        UUID actor = UUID.randomUUID();
        audit.recordGlobalViewRequest(actor, "root", "GET", "/patients/{id}");
        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(row.capture());
        assertThat(row.getValue().getUserId()).isEqualTo(actor);
        assertThat(row.getValue().getResourceId()).isEqualTo("/patients/{id}");
        assertThat(row.getValue().getStatus()).isEqualTo(AuditStatus.SUCCESS);

        when(auditEventLogService.logEvent(org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("down"));
        assertThatCode(() -> audit.recordGlobalViewRequest(actor, "root", "GET", "/x")).doesNotThrowAnyException();
        assertThatCode(() -> audit.recordRefusal(actor, "root", UUID.randomUUID(),
            com.example.hms.security.tenant.ActingScope.Reason.NOT_PERMITTED,
            com.example.hms.security.tenant.ActingScope.Source.HEADER)).doesNotThrowAnyException();
    }
}
