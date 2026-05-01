package com.example.hms.service.integration.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2ExportOutbox;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2ExportStatus;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2OutboxStatus;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.integration.Dhis2ExportOutboxRepository;
import com.example.hms.repository.integration.Dhis2ExportRunRepository;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.AggregatedDataValue;
import com.example.hms.service.integration.DhisAdxAggregator;
import com.example.hms.service.integration.DhisHttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Dhis2ExportRunPersistenceTest {

    @Mock private Dhis2ExportRunRepository runRepository;
    @Mock private Dhis2ExportOutboxRepository outboxRepository;
    @Mock private Dhis2FacilityConfigRepository facilityConfigRepository;
    @Mock private HospitalRepository hospitalRepository;

    private Dhis2ExportRunPersistence persistence;
    private UUID hospitalId;
    private Hospital hospital;
    private Dhis2FacilityConfig config;

    @BeforeEach
    void setUp() {
        persistence = new Dhis2ExportRunPersistence(runRepository, outboxRepository,
            facilityConfigRepository, hospitalRepository);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        config = Dhis2FacilityConfig.builder()
            .hospital(hospital)
            .baseUrl("https://dhis2.example.org")
            .authMode(Dhis2AuthMode.PAT)
            .authSecretEnvVar("DHIS2_TOKEN")
            .defaultPeriodType(Dhis2PeriodType.MONTHLY)
            .active(true)
            .build();
    }

    @Test
    @DisplayName("persistPending saves run + N outbox rows in order")
    void persistPendingHappyPath() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = persistence.persistPending(hospitalId, "DS00000DEFK", "202604", null,
            new DhisAdxAggregator.AggregationResult(
                List.of(
                    new AggregatedDataValue("OU000000001", "DE000000001", null, "12"),
                    new AggregatedDataValue("OU000000001", "DE000000002", "CC000000001", "7")
                ),
                1, "OU000000001"));

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.PENDING);
        assertThat(result.getValueCount()).isEqualTo(2);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getRequestId()).isNotNull();
        verify(runRepository, times(1)).save(any());
        verify(outboxRepository, times(1)).saveAll(any());
    }

    @Test
    @DisplayName("persistPending throws on missing hospital")
    void persistPendingMissingHospital() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        var aggregated = new DhisAdxAggregator.AggregationResult(List.of(), 0, "OU000000001");
        assertThatThrownBy(() ->
            persistence.persistPending(hospitalId, "DS00000DEFK", "202604", null, aggregated))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("finalizeEmpty stamps SUCCESS and httpStatus 204")
    void finalizeEmpty() {
        Dhis2ExportRun run = pendingRun();
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = persistence.finalizeEmpty(run);

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.SUCCESS);
        assertThat(result.getHttpStatus()).isEqualTo(204);
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("finalizeFailed stamps FAILED and marks all outbox rows FAILED")
    void finalizeFailed() {
        Dhis2ExportRun run = pendingRun();
        run.setId(UUID.randomUUID());
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Dhis2ExportOutbox row = pendingOutbox(run);
        when(outboxRepository.findByRun_Id(run.getId())).thenReturn(List.of(row));

        var result = persistence.finalizeFailed(run, 500, "boom");

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.FAILED);
        assertThat(result.getHttpStatus()).isEqualTo(500);
        assertThat(result.getErrorMessage()).isEqualTo("boom");
        assertThat(row.getStatus()).isEqualTo(Dhis2OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo("boom");
    }

    @Test
    @DisplayName("finalizeReconciled: 200 + ignored=0 -> SUCCESS, outbox SENT, lastExportAt updated")
    void finalizeReconciledSuccess() {
        Dhis2ExportRun run = pendingRun();
        run.setId(UUID.randomUUID());
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Dhis2ExportOutbox row = pendingOutbox(run);
        when(outboxRepository.findByRun_Id(run.getId())).thenReturn(List.of(row));

        var result = persistence.finalizeReconciled(run, config,
            new DhisHttpResponse(200, 1, 0, "{}"));

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.SUCCESS);
        assertThat(row.getStatus()).isEqualTo(Dhis2OutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(config.getLastExportAt()).isNotNull();
        verify(facilityConfigRepository, times(1)).save(eq(config));
    }

    @Test
    @DisplayName("finalizeReconciled: 200 + ignored>0 -> PARTIAL, outbox SENT, error message set")
    void finalizeReconciledPartial() {
        Dhis2ExportRun run = pendingRun();
        run.setId(UUID.randomUUID());
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Dhis2ExportOutbox row = pendingOutbox(run);
        when(outboxRepository.findByRun_Id(run.getId())).thenReturn(List.of(row));

        var result = persistence.finalizeReconciled(run, config,
            new DhisHttpResponse(200, 1, 1, "{}"));

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.PARTIAL);
        assertThat(result.getErrorMessage()).contains("ignored 1");
        assertThat(row.getStatus()).isEqualTo(Dhis2OutboxStatus.SENT);
    }

    @Test
    @DisplayName("finalizeReconciled: 4xx -> FAILED, outbox FAILED, lastExportAt NOT updated")
    void finalizeReconciledFailed() {
        Dhis2ExportRun run = pendingRun();
        run.setId(UUID.randomUUID());
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Dhis2ExportOutbox row = pendingOutbox(run);
        when(outboxRepository.findByRun_Id(run.getId())).thenReturn(List.of(row));

        var result = persistence.finalizeReconciled(run, config,
            new DhisHttpResponse(401, 0, 0, "unauthorized"));

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.FAILED);
        assertThat(row.getStatus()).isEqualTo(Dhis2OutboxStatus.FAILED);
        assertThat(config.getLastExportAt()).isNull();
    }

    private Dhis2ExportRun pendingRun() {
        return Dhis2ExportRun.builder()
            .hospital(hospital)
            .datasetUid("DS00000DEFK")
            .periodIso("202604")
            .status(Dhis2ExportStatus.PENDING)
            .requestId(UUID.randomUUID())
            .build();
    }

    private Dhis2ExportOutbox pendingOutbox(Dhis2ExportRun run) {
        return Dhis2ExportOutbox.builder()
            .run(run)
            .periodIso("202604")
            .orgUnitUid("OU000000001")
            .dataElementUid("DE000000001")
            .value("1")
            .status(Dhis2OutboxStatus.PENDING)
            .attempts(0)
            .build();
    }
}
