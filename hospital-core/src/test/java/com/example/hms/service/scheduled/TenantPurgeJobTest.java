package com.example.hms.service.scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantPurgeJobTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private AuditEventLogService auditEventLogService;

    @Mock
    private OrganizationLifecycleStatusService lifecycleStatusService;

    @InjectMocks
    private TenantPurgeJob job;

    private Organization due(UUID id, String code) {
        Organization o = new Organization();
        o.setId(id);
        o.setName("Org " + code);
        o.setCode(code);
        o.setLifecycleState(OrganizationLifecycleState.PENDING_PURGE);
        o.setPurgeScheduledFor(Instant.now().minus(1, ChronoUnit.HOURS));
        return o;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(job, "enabled", false);
        ReflectionTestUtils.setField(job, "executeDeletion", false);
    }

    @Test
    void disabledJobShortCircuitsAndDoesNotTouchTheDb() {
        job.runSweep();

        verify(organizationRepository, never()).findDuePurges(any());
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void enabledJobWithNoDuePurgesIsANoop() {
        ReflectionTestUtils.setField(job, "enabled", true);
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of());

        job.runSweep();

        verify(organizationRepository).findDuePurges(any());
        verify(organizationRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    void enabledJobTransitionsDueOrgsToPurgedAndAuditsEachOne() {
        ReflectionTestUtils.setField(job, "enabled", true);
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Organization a = due(idA, "ACME");
        Organization b = due(idB, "BETA");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a, b));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        job.runSweep();

        assertThat(a.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
        assertThat(a.getPurgedAt()).isNotNull();
        assertThat(b.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
        verify(organizationRepository, times(2)).save(any(Organization.class));

        ArgumentCaptor<AuditEventRequestDTO> auditCap = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(auditCap.capture());
        assertThat(auditCap.getAllValues()).allMatch(e ->
            e.getEventType() == AuditEventType.TENANT_PURGED
                && "ORGANIZATION".equals(e.getEntityType()));

        verify(lifecycleStatusService).invalidate();
    }

    @Test
    void singleOrgFailureDoesNotPreventTheRestOfTheSweep() {
        ReflectionTestUtils.setField(job, "enabled", true);
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        Organization a = due(idA, "ACME");
        Organization b = due(idB, "BETA");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a, b));
        when(organizationRepository.save(same(a))).thenThrow(new RuntimeException("save fail"));
        when(organizationRepository.save(same(b))).thenAnswer(inv -> inv.getArgument(0));

        job.runSweep();

        // The save on `a` threw — DB state was not committed for `a`. The
        // in-memory mutation to PURGED is irrelevant; what matters is that
        // the failure didn't abort the sweep and `b` was successfully audited.
        verify(organizationRepository, times(1)).save(same(a));
        verify(organizationRepository, times(1)).save(same(b));
        verify(auditEventLogService, times(1)).logEvent(any()); // only b
        verify(lifecycleStatusService).invalidate();
    }

    @Test
    void executeDeletionFlagDoesNotChangeBehaviourInMvp2() {
        // Future MVPs will gate cascade deletes behind this flag. For MVP-2 we
        // pin the contract: the flag is recognised but the job only flips state.
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "executeDeletion", true);
        Organization a = due(UUID.randomUUID(), "ACME");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

        job.runSweep();

        assertThat(a.getLifecycleState()).isEqualTo(OrganizationLifecycleState.PURGED);
        // No cascade-delete repository methods exist yet — the job logs a
        // warning and proceeds with the state transition only.
        verify(organizationRepository, times(1)).save(any(Organization.class));
    }
}
