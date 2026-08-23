package com.example.hms.service.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.ReportPeriod;
import com.example.hms.enums.ReportRunStatus;
import com.example.hms.enums.ReportType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.model.platform.ReportRun;
import com.example.hms.repository.ReportDefinitionRepository;
import com.example.hms.repository.ReportRunRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.reporting.ReportGenerationService.GeneratedReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The exactly-once report engine (P3 #25a): insert-first claim, per-run
 * failure isolation, and the manual retry/refusal contract.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledReportServiceTest {

    @Mock private ReportDefinitionRepository definitionRepository;
    @Mock private ReportRunRepository runRepository;
    @Mock private ReportGenerationService generationService;
    @Mock private EmailService emailService;

    private ScheduledReportService service;
    private ReportDefinition definition;

    @BeforeEach
    void setUp() {
        service = new ScheduledReportService(
            definitionRepository, runRepository, generationService, emailService);

        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        definition = ReportDefinition.builder()
            .hospital(hospital)
            .name("Monthly encounters")
            .reportType(ReportType.ENCOUNTER_ACTIVITY)
            .period(ReportPeriod.MONTHLY)
            .recipients("a@example.org, b@example.org")
            .build();
        definition.setId(UUID.randomUUID());

        lenient().when(runRepository.saveAndFlush(any(ReportRun.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(runRepository.save(any(ReportRun.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(generationService.generate(any(), anyString()))
            .thenReturn(new GeneratedReport("csv".getBytes(), 3, "report.csv"));
    }

    @Test
    void sweepEmitsThePriorClosedPeriodOnce() {
        when(definitionRepository.findByActiveTrue()).thenReturn(List.of(definition));
        when(runRepository.findByDefinition_IdAndPeriodToken(any(), anyString()))
            .thenReturn(Optional.empty());

        int emitted = service.sweep();

        assertThat(emitted).isEqualTo(1);
        verify(emailService).sendWithAttachment(
            eqList("a@example.org", "b@example.org"), anyList(), anyList(),
            anyString(), anyString(), any(byte[].class), anyString(), anyString());
    }

    private static List<String> eqList(String... values) {
        return org.mockito.ArgumentMatchers.eq(List.of(values));
    }

    @Test
    void anAlreadyClaimedPeriodIsSkippedWithoutEmail() {
        when(definitionRepository.findByActiveTrue()).thenReturn(List.of(definition));
        when(runRepository.findByDefinition_IdAndPeriodToken(any(), anyString()))
            .thenReturn(Optional.of(ReportRun.builder()
                .definition(definition).periodToken("x")
                .status(ReportRunStatus.SUCCEEDED).build()));

        assertThat(service.sweep()).isZero();
        verify(emailService, never()).sendWithAttachment(
            anyList(), anyList(), anyList(), anyString(), anyString(),
            any(byte[].class), anyString(), anyString());
    }

    @Test
    void aRacingClaimLosesQuietlyOnTheSweepPath() {
        // The UNIQUE constraint fires under a concurrent sweep instance.
        when(runRepository.saveAndFlush(any(ReportRun.class)))
            .thenThrow(new DataIntegrityViolationException("uq_report_run_period"));
        when(runRepository.findByDefinition_IdAndPeriodToken(any(), anyString()))
            .thenReturn(Optional.of(ReportRun.builder()
                .definition(definition).periodToken("202607")
                .status(ReportRunStatus.GENERATING).build()));

        assertThat(service.execute(definition, "202607", false)).isNull();
        verify(emailService, never()).sendWithAttachment(
            anyList(), anyList(), anyList(), anyString(), anyString(),
            any(byte[].class), anyString(), anyString());
    }

    @Test
    void generationFailureMarksTheRunFailedWithoutKillingTheSweep() {
        when(generationService.generate(any(), anyString()))
            .thenThrow(new RuntimeException("query exploded"));

        ReportRun run = service.execute(definition, "202607", false);

        assertThat(run.getStatus()).isEqualTo(ReportRunStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("query exploded");
    }

    @Test
    void manualRetryOfAFailedPeriodReusesTheRow() {
        ReportRun failed = ReportRun.builder()
            .definition(definition).periodToken("202607")
            .status(ReportRunStatus.FAILED).errorMessage("boom").build();
        failed.setId(UUID.randomUUID());
        when(runRepository.saveAndFlush(any(ReportRun.class)))
            .thenThrow(new DataIntegrityViolationException("uq_report_run_period"))
            .thenAnswer(inv -> inv.getArgument(0));
        when(runRepository.findByDefinition_IdAndPeriodToken(definition.getId(), "202607"))
            .thenReturn(Optional.of(failed));

        ReportRun run = service.execute(definition, "202607", true);

        assertThat(run.getId()).isEqualTo(failed.getId());
        assertThat(run.getStatus()).isEqualTo(ReportRunStatus.SUCCEEDED);
        assertThat(run.getErrorMessage()).isNull();
    }

    @Test
    void manualDuplicateOfASucceededPeriodIsRefused() {
        when(runRepository.saveAndFlush(any(ReportRun.class)))
            .thenThrow(new DataIntegrityViolationException("uq_report_run_period"));
        when(runRepository.findByDefinition_IdAndPeriodToken(definition.getId(), "202607"))
            .thenReturn(Optional.of(ReportRun.builder()
                .definition(definition).periodToken("202607")
                .status(ReportRunStatus.SUCCEEDED).build()));

        assertThatThrownBy(() -> service.execute(definition, "202607", true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already generated");
        verify(emailService, never()).sendWithAttachment(
            anyList(), anyList(), anyList(), anyString(), anyString(),
            any(byte[].class), anyString(), anyString());
    }

    @Test
    void manualFailureSurfacesTheErrorVerbatim() {
        when(generationService.generate(any(), anyString()))
            .thenThrow(new BusinessException("Unparseable period token 'garbage'"));

        assertThatThrownBy(() -> service.execute(definition, "garbage", true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unparseable");
    }
}
