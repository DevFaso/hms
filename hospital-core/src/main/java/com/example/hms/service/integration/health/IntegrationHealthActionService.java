package com.example.hms.service.integration.health;

import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.IntegrationHealthEvent;
import com.example.hms.payload.dto.superadmin.IntegrationHistoryBucketDTO;
import com.example.hms.payload.dto.superadmin.IntegrationProbeResultDTO;
import com.example.hms.repository.integration.IntegrationHealthEventRepository;
import com.example.hms.service.integration.probe.IntegrationConnectivityProbe;
import com.example.hms.service.integration.probe.Probe;
import com.example.hms.service.integration.probe.Resyncable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Test-connection / Re-sync / history actions for the Integration
 * Health Console (MVP-c batch — MVP-3b).
 *
 * <p>Probes and re-syncs are dispatched to the matching SPI bean by
 * {@code integration_id}. Outcomes feed back through the existing
 * {@link IntegrationHealthRecorder} so the inventory view + the new
 * time-series both stay in sync. Re-sync is asynchronous so the UI
 * returns immediately; the recorder captures success / failure as
 * the work completes.
 */
@Service
@Slf4j
public class IntegrationHealthActionService {

    private final Map<String, IntegrationConnectivityProbe> probesById;
    private final Map<String, Resyncable> resyncablesById;
    private final IntegrationHealthRecorder recorder;
    private final IntegrationHealthEventRepository eventRepository;

    public IntegrationHealthActionService(List<IntegrationConnectivityProbe> probes,
                                          List<Resyncable> resyncables,
                                          IntegrationHealthRecorder recorder,
                                          IntegrationHealthEventRepository eventRepository) {
        this.probesById = probes.stream()
            .collect(Collectors.toUnmodifiableMap(IntegrationConnectivityProbe::integrationId, p -> p,
                (a, b) -> a));
        this.resyncablesById = resyncables.stream()
            .collect(Collectors.toUnmodifiableMap(Resyncable::integrationId, r -> r,
                (a, b) -> a));
        this.recorder = recorder;
        this.eventRepository = eventRepository;
    }

    /**
     * Run the registered probe for {@code integrationId} and record the
     * outcome through the health recorder. Returns 404 (via
     * {@link ResourceNotFoundException}) if no probe is registered.
     */
    public IntegrationProbeResultDTO testConnection(String integrationId, UUID organizationId) {
        IntegrationConnectivityProbe probe = Optional.ofNullable(probesById.get(integrationId))
            .orElseThrow(() -> new ResourceNotFoundException(
                "No connectivity probe registered for integration: " + integrationId));

        long start = System.nanoTime();
        Probe outcome;
        try {
            outcome = probe.probe();
            if (outcome == null) {
                outcome = Probe.failed("Probe returned null result");
            }
        } catch (RuntimeException ex) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            String msg = "Probe threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            recorder.recordFailure(integrationId, organizationId, msg, latency);
            return IntegrationProbeResultDTO.builder()
                .integrationId(integrationId)
                .ok(false).latencyMs(latency).message(msg).build();
        }

        if (outcome.ok()) {
            recorder.recordSuccess(integrationId, organizationId, outcome.latencyMs());
        } else {
            recorder.recordFailure(integrationId, organizationId, outcome.message(), outcome.latencyMs());
        }
        return IntegrationProbeResultDTO.builder()
            .integrationId(integrationId)
            .ok(outcome.ok())
            .latencyMs(outcome.latencyMs())
            .message(outcome.message())
            .build();
    }

    /**
     * Trigger an asynchronous re-sync for {@code integrationId}. Throws
     * {@link BusinessRuleException} (HTTP 422 via the ControllerAdvice
     * mapping) when the integration is registered but has no Resyncable
     * impl — the operator can then decide whether to wait for the spec
     * or skip the action. {@link ResourceNotFoundException} (404) when
     * the integration is unknown entirely.
     */
    @Async
    public void resync(String integrationId, UUID organizationId) {
        Resyncable resync = resyncablesById.get(integrationId);
        if (resync == null) {
            // The controller pre-validates so this branch is the
            // belt-and-braces backup; logging only.
            log.warn("[INTEGRATION-RESYNC] No Resyncable impl for {} — async call dropped",
                integrationId);
            return;
        }
        try {
            resync.resync(organizationId);
        } catch (RuntimeException ex) {
            log.warn("[INTEGRATION-RESYNC] {} threw: {}", integrationId, ex.getMessage());
            recorder.recordFailure(integrationId, organizationId,
                "Re-sync failed: " + ex.getMessage(), null);
        }
    }

    /** Pre-flight check used by the controller before dispatching @Async. */
    public boolean supportsResync(String integrationId) {
        return resyncablesById.containsKey(integrationId);
    }

    public boolean isKnownIntegration(String integrationId) {
        return probesById.containsKey(integrationId) || resyncablesById.containsKey(integrationId);
    }

    /**
     * Bucketed 24h history for the given integration. Buckets are
     * truncated to the hour so a sparkline can render directly.
     */
    public List<IntegrationHistoryBucketDTO> getHistory(String integrationId, int windowHours) {
        if (windowHours <= 0 || windowHours > 168) {
            throw new BusinessRuleException("windowHours must be between 1 and 168 (got " + windowHours + ")");
        }
        LocalDateTime since = LocalDateTime.now().minus(windowHours, ChronoUnit.HOURS);
        List<IntegrationHealthEvent> rows = eventRepository.findRecentForIntegration(integrationId, since);

        // TreeMap keeps buckets sorted ascending so the sparkline is
        // chronological without an extra sort pass.
        Map<LocalDateTime, long[]> buckets = new TreeMap<>();
        for (IntegrationHealthEvent ev : rows) {
            LocalDateTime bucket = ev.getRecordedAt().truncatedTo(ChronoUnit.HOURS);
            long[] counts = buckets.computeIfAbsent(bucket, k -> new long[2]);
            switch (ev.getStatus()) {
                case HEALTHY -> counts[0]++;
                case FAILING, DEGRADED -> counts[1]++;
                default -> { /* NO_HISTORY etc — not counted */ }
            }
        }
        return buckets.entrySet().stream()
            .map(e -> IntegrationHistoryBucketDTO.builder()
                .bucketStart(e.getKey())
                .successCount(e.getValue()[0])
                .failureCount(e.getValue()[1])
                .build())
            .toList();
    }
}
