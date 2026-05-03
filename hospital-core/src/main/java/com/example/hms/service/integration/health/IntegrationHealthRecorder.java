package com.example.hms.service.integration.health;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.model.Organization;
import com.example.hms.model.integration.IntegrationHealthEvent;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import com.example.hms.repository.IntegrationHealthSnapshotRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.integration.IntegrationHealthEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Side-channel recorder that integration call sites invoke after each success
 * or failure to keep the {@code integration_health_snapshots} table fresh.
 *
 * <p>Runs in its own {@code REQUIRES_NEW} transaction so a failure here never
 * unrolls the caller's primary unit of work (e.g. an eligibility check that
 * succeeded must not be rolled back because the snapshot upsert hit a
 * constraint). All recorder methods are non-throwing — they log and swallow
 * — for the same reason.
 *
 * <p>The 24h rolling counters reset whenever the current snapshot's
 * {@code countsWindowStartedAt} is older than 24h, so the window is a
 * cheap-to-compute trailing slice rather than a true sliding window. Good
 * enough for the console, and doesn't require a scheduled sweep.
 */
@Slf4j
@Component
public class IntegrationHealthRecorder {

    static final Duration COUNTS_WINDOW = Duration.ofHours(24);

    private final IntegrationHealthSnapshotRepository repository;
    private final OrganizationRepository organizationRepository;
    private final Clock clock;

    /**
     * MVP-3b: optional time-series log. When wired, every recordSuccess /
     * recordFailure also inserts an event row so the Integration Health
     * Console can render a 24h sparkline. Optional (Spring will inject
     * null in tests / dev profiles that don't load the bean) so the
     * legacy snapshot-only behaviour stays available.
     */
    private final IntegrationHealthEventRepository eventRepository;

    @Autowired
    public IntegrationHealthRecorder(IntegrationHealthSnapshotRepository repository,
                                     OrganizationRepository organizationRepository,
                                     Clock clock,
                                     org.springframework.beans.factory.ObjectProvider<IntegrationHealthEventRepository>
                                         eventRepositoryProvider) {
        this.repository = repository;
        this.organizationRepository = organizationRepository;
        this.clock = clock;
        this.eventRepository = eventRepositoryProvider == null
            ? null : eventRepositoryProvider.getIfAvailable();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String integrationId, UUID organizationId) {
        recordSuccess(integrationId, organizationId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String integrationId, UUID organizationId, Long latencyMs) {
        try {
            IntegrationHealthSnapshot snapshot = upsert(integrationId, organizationId);
            LocalDateTime now = LocalDateTime.now(clock);
            rollWindow(snapshot, now);
            snapshot.setLastSuccessAt(now);
            snapshot.setSuccessCount24h(snapshot.getSuccessCount24h() + 1);
            snapshot.setLastStatus(deriveStatus(snapshot, /* lastWasSuccess */ true));
            repository.save(snapshot);
            persistEvent(integrationId, organizationId, IntegrationHealthStatus.HEALTHY, latencyMs, null, now);
        } catch (RuntimeException ex) {
            log.warn("IntegrationHealthRecorder.recordSuccess failed for integration={} org={}: {}",
                integrationId, organizationId, ex.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String integrationId, UUID organizationId, String errorMessage) {
        recordFailure(integrationId, organizationId, errorMessage, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String integrationId, UUID organizationId, String errorMessage, Long latencyMs) {
        try {
            IntegrationHealthSnapshot snapshot = upsert(integrationId, organizationId);
            LocalDateTime now = LocalDateTime.now(clock);
            rollWindow(snapshot, now);
            snapshot.setLastFailureAt(now);
            snapshot.setFailureCount24h(snapshot.getFailureCount24h() + 1);
            snapshot.setLastErrorMessage(truncate(errorMessage));
            snapshot.setLastStatus(deriveStatus(snapshot, /* lastWasSuccess */ false));
            repository.save(snapshot);
            persistEvent(integrationId, organizationId, IntegrationHealthStatus.FAILING,
                latencyMs, truncate(errorMessage), now);
        } catch (RuntimeException ex) {
            log.warn("IntegrationHealthRecorder.recordFailure failed for integration={} org={}: {}",
                integrationId, organizationId, ex.getMessage());
        }
    }

    private void persistEvent(String integrationId, UUID organizationId,
                              IntegrationHealthStatus status, Long latencyMs,
                              String errorMessage, LocalDateTime recordedAt) {
        if (eventRepository == null) {
            return; // legacy snapshot-only mode
        }
        try {
            eventRepository.save(IntegrationHealthEvent.builder()
                .integrationId(integrationId)
                .organizationId(organizationId)
                .status(status)
                .latencyMs(latencyMs)
                .errorMessage(errorMessage)
                .recordedAt(recordedAt)
                .build());
        } catch (RuntimeException ex) {
            // Time-series persistence is best-effort — don't break the
            // snapshot upsert because the history insert hit a constraint.
            log.warn("IntegrationHealthRecorder time-series persist failed for integration={} org={}: {}",
                integrationId, organizationId, ex.getMessage());
        }
    }

    private IntegrationHealthSnapshot upsert(String integrationId, UUID organizationId) {
        return repository.findOneFor(integrationId, organizationId)
            .orElseGet(() -> {
                Organization org = organizationId == null
                    ? null
                    : organizationRepository.findById(organizationId).orElse(null);
                return IntegrationHealthSnapshot.builder()
                    .integrationId(integrationId)
                    .organization(org)
                    .lastStatus(IntegrationHealthStatus.NO_HISTORY)
                    .successCount24h(0)
                    .failureCount24h(0)
                    .build();
            });
    }

    private void rollWindow(IntegrationHealthSnapshot snapshot, LocalDateTime now) {
        LocalDateTime windowStart = snapshot.getCountsWindowStartedAt();
        if (windowStart == null || Duration.between(windowStart, now).compareTo(COUNTS_WINDOW) >= 0) {
            snapshot.setCountsWindowStartedAt(now);
            snapshot.setSuccessCount24h(0);
            snapshot.setFailureCount24h(0);
        }
    }

    /**
     * DEGRADED when both successes and failures are present in the window with
     * failures < 50% of total; FAILING when the most recent call failed or
     * failures >= 50% of total; HEALTHY only if the most recent call succeeded
     * AND the failure count is zero.
     */
    private IntegrationHealthStatus deriveStatus(IntegrationHealthSnapshot snapshot,
                                                 boolean lastWasSuccess) {
        int successes = snapshot.getSuccessCount24h();
        int failures = snapshot.getFailureCount24h();
        int total = successes + failures;
        if (total == 0) {
            return IntegrationHealthStatus.NO_HISTORY;
        }
        if (!lastWasSuccess) {
            return IntegrationHealthStatus.FAILING;
        }
        if (failures == 0) {
            return IntegrationHealthStatus.HEALTHY;
        }
        return failures * 2 >= total
            ? IntegrationHealthStatus.FAILING
            : IntegrationHealthStatus.DEGRADED;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
