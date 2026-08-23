package com.example.hms.service.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.ReportPeriod;
import com.example.hms.enums.ReportType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.service.reporting.ReportGenerationService.GeneratedReport;
import com.example.hms.service.reporting.ReportGenerationService.PeriodRange;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

/** Canned aggregate CSVs + period-token arithmetic (P3 #25a). */
@ExtendWith(MockitoExtension.class)
class ReportGenerationServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private AppointmentRepository appointmentRepository;

    private ReportGenerationService service;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new ReportGenerationService(encounterRepository, appointmentRepository);
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    private ReportDefinition definition(ReportType type, ReportPeriod period) {
        return ReportDefinition.builder()
            .hospital(hospital)
            .name("Test report")
            .reportType(type)
            .period(period)
            .recipients("admin@example.org")
            .build();
    }

    /* ── period tokens ─────────────────────────────────────────────── */

    @Test
    void priorPeriodTokens() {
        LocalDate today = LocalDate.of(2026, 8, 22);
        assertThat(ReportGenerationService.priorPeriodToken(ReportPeriod.DAILY, today))
            .isEqualTo("2026-08-21");
        assertThat(ReportGenerationService.priorPeriodToken(ReportPeriod.MONTHLY, today))
            .isEqualTo("202607");
        // 2026-08-22 is in ISO week 34, so the prior week is 33.
        assertThat(ReportGenerationService.priorPeriodToken(ReportPeriod.WEEKLY, today))
            .isEqualTo("2026-W33");
    }

    @Test
    void tokensRoundTripToTheirDateRanges() {
        PeriodRange day = ReportGenerationService.parseToken(ReportPeriod.DAILY, "2026-08-21");
        assertThat(day.start()).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(day.end()).isEqualTo(LocalDate.of(2026, 8, 21));

        PeriodRange week = ReportGenerationService.parseToken(ReportPeriod.WEEKLY, "2026-W33");
        assertThat(week.start()).isEqualTo(LocalDate.of(2026, 8, 10)); // Monday
        assertThat(week.end()).isEqualTo(LocalDate.of(2026, 8, 16));   // Sunday

        PeriodRange month = ReportGenerationService.parseToken(ReportPeriod.MONTHLY, "202607");
        assertThat(month.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(month.end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void aMalformedTokenIsARefusalNotAGuess() {
        assertThatThrownBy(() ->
            ReportGenerationService.parseToken(ReportPeriod.DAILY, "next Tuesday"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unparseable");
    }

    /* ── canned CSVs ───────────────────────────────────────────────── */

    @Test
    void encounterActivityAggregatesPerDayWithNoPatientData() {
        Encounter completed = new Encounter();
        completed.setEncounterDate(LocalDateTime.of(2026, 8, 21, 9, 0));
        completed.setStatus(EncounterStatus.COMPLETED);
        Encounter cancelled = new Encounter();
        cancelled.setEncounterDate(LocalDateTime.of(2026, 8, 21, 11, 0));
        cancelled.setStatus(EncounterStatus.CANCELLED);
        when(encounterRepository.findByHospital_IdAndEncounterDateBetween(
            eq(hospital.getId()), any(), any(), any()))
            .thenReturn(new PageImpl<>(List.of(completed, cancelled)));

        GeneratedReport report = service.generate(
            definition(ReportType.ENCOUNTER_ACTIVITY, ReportPeriod.DAILY), "2026-08-21");

        String csv = new String(report.content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("date,encounters_total,completed,cancelled");
        assertThat(csv).contains("2026-08-21,2,1,1");
        assertThat(report.rowCount()).isEqualTo(1);
        assertThat(report.filename()).isEqualTo("encounter_activity_20260821.csv");
    }

    @Test
    void appointmentActivityCountsTerminalStatuses() {
        Appointment done = new Appointment();
        done.setAppointmentDate(LocalDate.of(2026, 7, 3));
        done.setStatus(AppointmentStatus.COMPLETED);
        Appointment noShow = new Appointment();
        noShow.setAppointmentDate(LocalDate.of(2026, 7, 3));
        noShow.setStatus(AppointmentStatus.NO_SHOW);
        Appointment scheduled = new Appointment();
        scheduled.setAppointmentDate(LocalDate.of(2026, 7, 4));
        scheduled.setStatus(AppointmentStatus.SCHEDULED);
        lenient().when(appointmentRepository.findByHospital_IdAndAppointmentDateBetween(
            eq(hospital.getId()), any(), any()))
            .thenReturn(List.of(done, noShow, scheduled));

        GeneratedReport report = service.generate(
            definition(ReportType.APPOINTMENT_ACTIVITY, ReportPeriod.MONTHLY), "202607");

        String csv = new String(report.content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("date,appointments_total,completed,cancelled,no_show");
        assertThat(csv).contains("2026-07-03,2,1,0,1");
        assertThat(csv).contains("2026-07-04,1,0,0,0");
        assertThat(report.rowCount()).isEqualTo(2);
    }
}
