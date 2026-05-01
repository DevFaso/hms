package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DhisAdxSchedulerTest {

    @Mock private Dhis2FacilityConfigRepository facilityConfigRepository;
    @Mock private DhisAdxExportService exportService;

    private DhisAdxScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DhisAdxScheduler(facilityConfigRepository, exportService);
    }

    @Test
    @DisplayName("runSweep iterates every active facility config")
    void iteratesAllActive() {
        Dhis2FacilityConfig a = activeConfig("DSAAAAAAAA1", Dhis2PeriodType.MONTHLY);
        Dhis2FacilityConfig b = activeConfig("DSBBBBBBBB1", Dhis2PeriodType.MONTHLY);
        when(facilityConfigRepository.findByActiveTrue()).thenReturn(List.of(a, b));

        scheduler.runSweep();

        verify(exportService, times(1)).triggerImmunizationsExport(
            eq(a.getHospital().getId()), eq("DSAAAAAAAA1"),
            eq(Dhis2PeriodType.MONTHLY), any(), eq(null));
        verify(exportService, times(1)).triggerImmunizationsExport(
            eq(b.getHospital().getId()), eq("DSBBBBBBBB1"),
            eq(Dhis2PeriodType.MONTHLY), any(), eq(null));
    }

    @Test
    @DisplayName("config without defaultDatasetUid is skipped (no trigger call)")
    void skipsConfigWithNoDataset() {
        Dhis2FacilityConfig noDs = activeConfig(null, Dhis2PeriodType.MONTHLY);
        when(facilityConfigRepository.findByActiveTrue()).thenReturn(List.of(noDs));

        scheduler.runSweep();

        verify(exportService, never()).triggerImmunizationsExport(
            any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("monthly: prior-period token is YYYYMM for the previous month")
    void monthlyPriorPeriod() {
        Dhis2FacilityConfig cfg = activeConfig("DSMMMMMMMM1", Dhis2PeriodType.MONTHLY);
        when(facilityConfigRepository.findByActiveTrue()).thenReturn(List.of(cfg));

        scheduler.runSweep();

        ArgumentCaptor<String> period = ArgumentCaptor.forClass(String.class);
        verify(exportService).triggerImmunizationsExport(
            any(), any(), any(), period.capture(), any());
        assertThat(period.getValue()).matches("\\d{6}");
    }

    @Test
    @DisplayName("yearly: prior-period token is the previous YYYY")
    void yearlyPriorPeriod() {
        Dhis2FacilityConfig cfg = activeConfig("DSYYYYYYYY1", Dhis2PeriodType.YEARLY);
        when(facilityConfigRepository.findByActiveTrue()).thenReturn(List.of(cfg));

        scheduler.runSweep();

        ArgumentCaptor<String> period = ArgumentCaptor.forClass(String.class);
        verify(exportService).triggerImmunizationsExport(
            any(), any(), any(), period.capture(), any());
        assertThat(period.getValue()).matches("\\d{4}");
    }

    @Test
    @DisplayName("one facility's failure does not abort the sweep loop")
    void oneFailureDoesNotAbortLoop() {
        Dhis2FacilityConfig good = activeConfig("DSGOODGOOD1", Dhis2PeriodType.MONTHLY);
        Dhis2FacilityConfig bad = activeConfig("DSBADBADBA1", Dhis2PeriodType.MONTHLY);
        when(facilityConfigRepository.findByActiveTrue()).thenReturn(List.of(bad, good));
        when(exportService.triggerImmunizationsExport(
            eq(bad.getHospital().getId()), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));

        scheduler.runSweep();

        verify(exportService, times(1)).triggerImmunizationsExport(
            eq(good.getHospital().getId()), any(), any(), any(), any());
    }

    private Dhis2FacilityConfig activeConfig(String datasetUid, Dhis2PeriodType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return Dhis2FacilityConfig.builder()
            .hospital(h)
            .baseUrl("https://dhis2.example.org")
            .authMode(Dhis2AuthMode.PAT)
            .authSecretEnvVar("DHIS2_TOKEN")
            .defaultPeriodType(type)
            .defaultDatasetUid(datasetUid)
            .active(true)
            .build();
    }
}
