package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2ExportStatus;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.impl.Dhis2ExportRunPersistence;
import com.example.hms.service.integration.impl.DhisAdxExportServiceImpl;
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
class DhisAdxExportServiceImplTest {

    @Mock private Dhis2FacilityConfigRepository facilityConfigRepository;
    @Mock private Dhis2ExportRunPersistence persistence;
    @Mock private DhisAdxAggregator aggregator;
    @Mock private DhisAdxXmlWriter xmlWriter;
    @Mock private DhisHttpClient httpClient;

    private DhisAdxExportServiceImpl service;
    private UUID hospitalId;
    private Hospital hospital;
    private Dhis2FacilityConfig config;
    private Dhis2ExportRun pendingRun;
    private final String datasetUid = "DS00000DEFK";

    @BeforeEach
    void setUp() {
        service = new DhisAdxExportServiceImpl(facilityConfigRepository, persistence,
            aggregator, xmlWriter, httpClient);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setDhis2OrgUnitUid("OU000000001");
        config = Dhis2FacilityConfig.builder()
            .hospital(hospital)
            .baseUrl("https://dhis2.example.org")
            .authMode(Dhis2AuthMode.PAT)
            .authSecretEnvVar("DHIS2_TOKEN")
            .defaultPeriodType(Dhis2PeriodType.MONTHLY)
            .defaultDatasetUid(datasetUid)
            .active(true)
            .build();
        pendingRun = Dhis2ExportRun.builder()
            .hospital(hospital)
            .datasetUid(datasetUid)
            .periodIso("202604")
            .status(Dhis2ExportStatus.PENDING)
            .requestId(UUID.randomUUID())
            .build();
        pendingRun.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("missing facility config -> ResourceNotFoundException, no work")
    void missingConfig() {
        when(facilityConfigRepository.findByHospital_IdAndActiveTrue(hospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.triggerImmunizationsExport(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, "202604", null))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(persistence, never()).persistPending(any(), any(), any(), any(), any());
        verify(httpClient, never()).postDataValueSet(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("periodType mismatch with facility default -> BusinessException")
    void periodTypeMismatch() {
        when(facilityConfigRepository.findByHospital_IdAndActiveTrue(hospitalId))
            .thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.triggerImmunizationsExport(
            hospitalId, datasetUid, Dhis2PeriodType.WEEKLY, "2026W17", null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("does not match facility default");

        verify(httpClient, never()).postDataValueSet(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("empty aggregation -> finalizeEmpty, no HTTP call")
    void emptyAggregation() {
        wireConfig();
        when(aggregator.aggregateImmunizations(eq(hospitalId), eq(datasetUid),
            eq(Dhis2PeriodType.MONTHLY), any(), any()))
            .thenReturn(new DhisAdxAggregator.AggregationResult(List.of(), 0, "OU000000001"));
        when(persistence.persistPending(any(), any(), any(), any(), any())).thenReturn(pendingRun);
        Dhis2ExportRun finalized = withStatus(Dhis2ExportStatus.SUCCESS);
        when(persistence.finalizeEmpty(pendingRun)).thenReturn(finalized);

        Dhis2ExportRun result = service.triggerImmunizationsExport(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, "202604", null);

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.SUCCESS);
        verify(httpClient, never()).postDataValueSet(any(), any(), any(), any(), any());
        verify(persistence, never()).finalizeReconciled(any(), any(), any());
    }

    @Test
    @DisplayName("happy path: 2xx with no ignored -> finalizeReconciled")
    void happyPath() {
        wireConfigAndAggregation();
        when(xmlWriter.build(any(), any(), any(), any(), any())).thenReturn("<adx/>");
        when(persistence.persistPending(any(), any(), any(), any(), any())).thenReturn(pendingRun);
        when(httpClient.postDataValueSet(any(), any(), any(), anyString(), any()))
            .thenReturn(new DhisHttpResponse(200, 1, 0, "{}"));
        Dhis2ExportRun finalized = withStatus(Dhis2ExportStatus.SUCCESS);
        when(persistence.finalizeReconciled(eq(pendingRun), eq(config), any())).thenReturn(finalized);

        Dhis2ExportRun result = service.triggerImmunizationsExport(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, "202604", null);

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.SUCCESS);
        verify(persistence, times(1)).finalizeReconciled(eq(pendingRun), eq(config), any());
    }

    @Test
    @DisplayName("HTTP exception -> finalizeFailed with diagnostic")
    void httpExceptionFinalized() {
        wireConfigAndAggregation();
        when(xmlWriter.build(any(), any(), any(), any(), any())).thenReturn("<adx/>");
        when(persistence.persistPending(any(), any(), any(), any(), any())).thenReturn(pendingRun);
        when(httpClient.postDataValueSet(any(), any(), any(), anyString(), any()))
            .thenThrow(new RuntimeException("boom"));
        Dhis2ExportRun finalized = withStatus(Dhis2ExportStatus.FAILED);
        when(persistence.finalizeFailed(eq(pendingRun), eq(0), anyString())).thenReturn(finalized);

        Dhis2ExportRun result = service.triggerImmunizationsExport(
            hospitalId, datasetUid, Dhis2PeriodType.MONTHLY, "202604", null);

        assertThat(result.getStatus()).isEqualTo(Dhis2ExportStatus.FAILED);
    }

    private void wireConfig() {
        when(facilityConfigRepository.findByHospital_IdAndActiveTrue(hospitalId))
            .thenReturn(Optional.of(config));
    }

    private void wireConfigAndAggregation() {
        wireConfig();
        when(aggregator.aggregateImmunizations(eq(hospitalId), eq(datasetUid),
            eq(Dhis2PeriodType.MONTHLY), any(), any()))
            .thenReturn(new DhisAdxAggregator.AggregationResult(
                List.of(new AggregatedDataValue("OU000000001", "DE000000001", null, "1")),
                0, "OU000000001"));
    }

    private Dhis2ExportRun withStatus(Dhis2ExportStatus status) {
        Dhis2ExportRun copy = Dhis2ExportRun.builder()
            .hospital(hospital)
            .datasetUid(datasetUid)
            .periodIso("202604")
            .status(status)
            .requestId(pendingRun.getRequestId())
            .build();
        copy.setId(pendingRun.getId());
        return copy;
    }
}
