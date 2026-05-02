package com.example.hms.service.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantPurgeExecutorTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private AuditEventLogService auditEventLogService;

    @InjectMocks
    private TenantPurgeExecutor executor;

    private Organization org;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setId(UUID.randomUUID());
        org.setName("Acme Health");
        org.setCode("ACME");
        org.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        org.setActive(false);
    }

    @Test
    void executePurgeFlipsStateToPurgedAndStampsPurgedAt() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, false);

        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
        assertThat(org.getPurgedAt()).isNotNull();
        assertThat(org.isActive()).isFalse();
        verify(organizationRepository, times(1)).save(org);
    }

    @Test
    void executePurgeEmitsTenantPurgedAuditEvent() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, false);

        ArgumentCaptor<AuditEventRequestDTO> auditCap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(auditCap.capture());
        AuditEventRequestDTO ev = auditCap.getValue();
        assertThat(ev.getEventType()).isEqualTo(AuditEventType.TENANT_PURGED);
        assertThat(ev.getEntityType()).isEqualTo("ORGANIZATION");
        assertThat(ev.getResourceId()).isEqualTo(org.getId().toString());
    }

    @Test
    void executeDeletionFlagDoesNotChangeBehaviourInMvp2() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.executePurge(org, true);

        // Cascade-delete is not implemented in MVP-2 — the executor logs a
        // warning and proceeds with the state transition only. No extra
        // repository interactions beyond the single save.
        verify(organizationRepository, times(1)).save(org);
    }

    @Test
    void auditFailureDoesNotPropagateOutOfTheExecutor() {
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditEventLogService.logEvent(any())).thenThrow(new RuntimeException("audit down"));

        // Should not throw — audit emission is best-effort by contract.
        executor.executePurge(org, false);

        assertThat(org.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
    }
}
