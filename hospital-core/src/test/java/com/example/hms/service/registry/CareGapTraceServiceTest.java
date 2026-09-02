package com.example.hms.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Defaulter tracing (Tier 2 item 36).
 *
 * <p>The dedupe test is the one that matters most: the sweep runs nightly
 * and every run sees the same overdue rows, so without the
 * one-recall-per-missed-date rule a defaulter would accumulate a recall a
 * day and the desk worklist would be unusable within a week.
 */
@ExtendWith(MockitoExtension.class)
class CareGapTraceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 5, 30);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private ProgramEnrollmentRepository enrollmentRepository;
    @Mock private PatientRecallRepository recallRepository;
    @Mock private MessageSource messageSource;

    private CareGapTraceService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CareGapTraceService(enrollmentRepository, recallRepository,
            messageSource, clock);
        ReflectionTestUtils.setField(service, "graceDays", 0L);
        ReflectionTestUtils.setField(service, "outreachLocale", "fr");
    }

    private static ProgramEnrollment overdueEnrollment(LocalDate missedDate) {
        ProgramEnrollment e = ProgramEnrollment.builder()
            .patient(patient())
            .hospital(hospital())
            .program(CareProgram.HIV)
            .status(ProgramEnrollmentStatus.ACTIVE)
            .enrolledOn(missedDate.minusDays(30))
            .visitCadenceDays(30)
            .nextExpectedVisit(missedDate)
            .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private static Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return h;
    }

    @Test
    @DisplayName("an overdue enrolment becomes a PROGRAM_RULE recall due on the missed date")
    void overdueBecomesRecall() {
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        when(enrollmentRepository.findOverdueActive(TODAY)).thenReturn(List.of(enrollment));
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), TODAY.minusDays(5))).thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("HIV programme visit overdue");

        int created = service.traceDefaulters();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<PatientRecall> captor = ArgumentCaptor.forClass(PatientRecall.class);
        verify(recallRepository).save(captor.capture());
        PatientRecall recall = captor.getValue();
        assertThat(recall.getSource()).isEqualTo(RecallSource.PROGRAM_RULE);
        assertThat(recall.getRecallType()).isEqualTo(RecallType.FOLLOW_UP);
        assertThat(recall.getStatus()).isEqualTo(RecallStatus.PENDING);
        assertThat(recall.getDueDate()).isEqualTo(TODAY.minusDays(5));
        assertThat(recall.getProgramEnrollment()).isSameAs(enrollment);
        assertThat(recall.getPatient()).isSameAs(enrollment.getPatient());
        assertThat(recall.getHospital()).isSameAs(enrollment.getHospital());
    }

    @Test
    @DisplayName("a missed date that already produced a recall is never traced twice")
    void dedupesByMissedDate() {
        // Any status counts - a recall the desk closed without a visit must
        // not be recreated, or the sweep fights the desk every night.
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        when(enrollmentRepository.findOverdueActive(TODAY)).thenReturn(List.of(enrollment));
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), TODAY.minusDays(5))).thenReturn(true);

        int created = service.traceDefaulters();

        assertThat(created).isZero();
        verify(recallRepository, never()).save(any());
    }

    @Test
    @DisplayName("a configured grace period shifts the cutoff handed to the query")
    void graceShiftsCutoff() {
        ReflectionTestUtils.setField(service, "graceDays", 7L);
        when(enrollmentRepository.findOverdueActive(TODAY.minusDays(7))).thenReturn(List.of());

        service.traceDefaulters();

        verify(enrollmentRepository).findOverdueActive(TODAY.minusDays(7));
    }

    @Test
    @DisplayName("one bad row does not stop the rest of the cohort")
    void oneBadRowDoesNotStopTheSweep() {
        ProgramEnrollment bad = overdueEnrollment(TODAY.minusDays(3));
        ProgramEnrollment good = overdueEnrollment(TODAY.minusDays(4));
        when(enrollmentRepository.findOverdueActive(TODAY)).thenReturn(List.of(bad, good));
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(any(), any()))
            .thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
            .thenReturn("reason");
        when(recallRepository.save(any()))
            .thenThrow(new IllegalStateException("boom"))
            .thenAnswer(inv -> inv.getArgument(0));

        int created = service.traceDefaulters();

        assertThat(created).isEqualTo(1);
        verify(recallRepository, times(2)).save(any());
    }
}
