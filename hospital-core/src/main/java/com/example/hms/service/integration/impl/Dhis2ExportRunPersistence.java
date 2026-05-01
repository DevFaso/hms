package com.example.hms.service.integration.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.Dhis2ExportOutbox;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2ExportStatus;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2OutboxStatus;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.integration.Dhis2ExportOutboxRepository;
import com.example.hms.repository.integration.Dhis2ExportRunRepository;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.AggregatedDataValue;
import com.example.hms.service.integration.DhisAdxAggregator;
import com.example.hms.service.integration.DhisHttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence helper for the DHIS2 export orchestrator.
 *
 * <p>Lives in a separate Spring bean so the {@code REQUIRES_NEW}
 * propagation actually fires through the AOP proxy (Sonar S6809).
 * Each method opens its own transaction so the orchestrator's HTTP
 * call sits between two distinct write boundaries: PENDING persists
 * before the network call, and the terminal status persists
 * regardless of whether the network throws.
 */
@Component
public class Dhis2ExportRunPersistence {

    private final Dhis2ExportRunRepository runRepository;
    private final Dhis2ExportOutboxRepository outboxRepository;
    private final Dhis2FacilityConfigRepository facilityConfigRepository;
    private final HospitalRepository hospitalRepository;

    public Dhis2ExportRunPersistence(Dhis2ExportRunRepository runRepository,
                                     Dhis2ExportOutboxRepository outboxRepository,
                                     Dhis2FacilityConfigRepository facilityConfigRepository,
                                     HospitalRepository hospitalRepository) {
        this.runRepository = runRepository;
        this.outboxRepository = outboxRepository;
        this.facilityConfigRepository = facilityConfigRepository;
        this.hospitalRepository = hospitalRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Dhis2ExportRun persistPending(UUID hospitalId,
                                         String datasetUid,
                                         String periodIso,
                                         UUID staffId,
                                         DhisAdxAggregator.AggregationResult aggregated) {
        final var hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + hospitalId));

        final Dhis2ExportRun run = Dhis2ExportRun.builder()
            .hospital(hospital)
            .datasetUid(datasetUid)
            .periodIso(periodIso)
            .triggeredByStaffId(staffId)
            .startedAt(LocalDateTime.now())
            .status(Dhis2ExportStatus.PENDING)
            .valueCount(aggregated.values().size())
            .skippedCount(aggregated.skippedCount())
            .requestId(UUID.randomUUID())
            .build();
        runRepository.save(run);

        final List<Dhis2ExportOutbox> outboxRows = new ArrayList<>(aggregated.values().size());
        for (AggregatedDataValue v : aggregated.values()) {
            outboxRows.add(Dhis2ExportOutbox.builder()
                .run(run)
                .periodIso(periodIso)
                .orgUnitUid(v.orgUnitUid())
                .dataElementUid(v.dataElementUid())
                .categoryOptionComboUid(v.categoryOptionComboUid())
                .value(v.value())
                .status(Dhis2OutboxStatus.PENDING)
                .attempts(0)
                .build());
        }
        outboxRepository.saveAll(outboxRows);
        return run;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Dhis2ExportRun finalizeEmpty(Dhis2ExportRun run) {
        run.setStatus(Dhis2ExportStatus.SUCCESS);
        run.setCompletedAt(LocalDateTime.now());
        run.setHttpStatus(204);
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Dhis2ExportRun finalizeFailed(Dhis2ExportRun run, int httpStatus, String message) {
        run.setStatus(Dhis2ExportStatus.FAILED);
        run.setHttpStatus(httpStatus);
        run.setErrorMessage(message);
        run.setCompletedAt(LocalDateTime.now());
        markAllOutbox(run.getId(), Dhis2OutboxStatus.FAILED, message);
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Dhis2ExportRun finalizeReconciled(Dhis2ExportRun run,
                                             Dhis2FacilityConfig config,
                                             DhisHttpResponse response) {
        run.setHttpStatus(response.httpStatus());
        run.setCompletedAt(LocalDateTime.now());

        final boolean http2xx = response.httpStatus() >= 200 && response.httpStatus() < 300;
        if (!http2xx) {
            run.setStatus(Dhis2ExportStatus.FAILED);
            run.setErrorMessage(truncate(response.body(), 2048));
            markAllOutbox(run.getId(), Dhis2OutboxStatus.FAILED, run.getErrorMessage());
            return runRepository.save(run);
        }

        final boolean partial = response.ignoredCount() > 0;
        run.setStatus(partial ? Dhis2ExportStatus.PARTIAL : Dhis2ExportStatus.SUCCESS);
        if (partial) {
            run.setErrorMessage("DHIS2 ignored " + response.ignoredCount() + " value(s); see body");
        }
        markAllOutbox(run.getId(), Dhis2OutboxStatus.SENT, null);

        config.setLastExportAt(LocalDateTime.now());
        facilityConfigRepository.save(config);

        return runRepository.save(run);
    }

    private void markAllOutbox(UUID runId, Dhis2OutboxStatus status, String error) {
        final var rows = outboxRepository.findByRun_Id(runId);
        for (Dhis2ExportOutbox row : rows) {
            row.setStatus(status);
            row.setAttempts(row.getAttempts() + 1);
            if (error != null) {
                row.setLastError(truncate(error, 1024));
            }
            if (status == Dhis2OutboxStatus.SENT) {
                row.setSentAt(LocalDateTime.now());
            }
        }
        outboxRepository.saveAll(rows);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
