package com.example.hms.service.scheduled;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.OrganizationLifecycleState;
import com.example.hms.model.Organization;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationLifecycleStatusService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link TenantPurgeJob}'s sweep loop.
 *
 * <p>The actual per-org purge logic now lives in {@link TenantPurgeExecutor}
 * (split out so each call runs in its own REQUIRES_NEW transaction). The
 * sweep test is therefore narrowed to: enabled-flag short-circuit, due-list
 * fetch, per-org delegation, failure isolation, and cache invalidation.
 */
@ExtendWith(MockitoExtension.class)
class TenantPurgeJobTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationLifecycleStatusService lifecycleStatusService;

    @Mock
    private TenantPurgeExecutor purgeExecutor;

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
        verify(purgeExecutor, never()).executePurge(any(), anyBoolean());
        verify(lifecycleStatusService, never()).invalidate();
    }

    @Test
    void enabledJobWithNoDuePurgesIsANoop() {
        ReflectionTestUtils.setField(job, "enabled", true);
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of());

        job.runSweep();

        verify(organizationRepository).findDuePurges(any());
        verify(purgeExecutor, never()).executePurge(any(), anyBoolean());
    }

    @Test
    void enabledJobDelegatesToTheExecutorOncePerDueOrg() {
        ReflectionTestUtils.setField(job, "enabled", true);
        Organization a = due(UUID.randomUUID(), "ACME");
        Organization b = due(UUID.randomUUID(), "BETA");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a, b));

        job.runSweep();

        verify(purgeExecutor).executePurge(same(a), eqBoolean(false));
        verify(purgeExecutor).executePurge(same(b), eqBoolean(false));
        verify(lifecycleStatusService).invalidate();
    }

    @Test
    void singleOrgFailureDoesNotPreventTheRestOfTheSweep() {
        ReflectionTestUtils.setField(job, "enabled", true);
        Organization a = due(UUID.randomUUID(), "ACME");
        Organization b = due(UUID.randomUUID(), "BETA");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a, b));
        // Each executePurge call is a REQUIRES_NEW transaction in production,
        // so a runtime exception escaping from the first call can be caught
        // by the loop without poisoning the second.
        doThrow(new RuntimeException("rolled back")).when(purgeExecutor).executePurge(same(a), eqBoolean(false));

        job.runSweep();

        verify(purgeExecutor, times(1)).executePurge(same(a), eqBoolean(false));
        verify(purgeExecutor, times(1)).executePurge(same(b), eqBoolean(false));
        verify(lifecycleStatusService).invalidate();
    }

    @Test
    void executeDeletionFlagIsForwardedToTheExecutor() {
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "executeDeletion", true);
        Organization a = due(UUID.randomUUID(), "ACME");
        when(organizationRepository.findDuePurges(any())).thenReturn(List.of(a));

        job.runSweep();

        verify(purgeExecutor).executePurge(same(a), eqBoolean(true));
    }

    /** Tiny helper to keep the verify call sites readable when paired with same(). */
    private static boolean eqBoolean(boolean expected) {
        return org.mockito.ArgumentMatchers.eq(expected);
    }
}
