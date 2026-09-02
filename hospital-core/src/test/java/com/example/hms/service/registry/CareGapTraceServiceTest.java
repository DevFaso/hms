package com.example.hms.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.CareProgram;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.AuditEventLogService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Defaulter tracing (Tier 2 item 36).
 *
 * <p>The staleness tests are the ones that matter now: the candidate read
 * and the write run in different transactions, and a visit recorded (or an
 * enrolment closed) between them must be seen by the per-row reload — not
 * raced into a recall for a patient who already came.
 */
@ExtendWith(MockitoExtension.class)
class CareGapTraceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 5, 30);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Mock private ProgramEnrollmentRepository enrollmentRepository;
    @Mock private PatientRecallRepository recallRepository;
    @Mock private MessageSource messageSource;
    @Mock private AuditEventLogService auditService;
    @Mock private TransactionTemplate transactionTemplate;

    private CareGapTraceService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CareGapTraceService(enrollmentRepository, recallRepository,
            messageSource, auditService, transactionTemplate, clock);
        ReflectionTestUtils.setField(service, "graceDays", 0L);
        ReflectionTestUtils.setField(service, "outreachLocale", "fr");
        // Execute each per-row callback inline; the template's transactional
        // semantics belong to Spring, the sweep's logic to these tests.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
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
        h.setName("General");
        return h;
    }

    /** One page of candidates, then the empty page that ends the loop. */
    private void candidates(ProgramEnrollment... enrollments) {
        when(enrollmentRepository.findUntracedOverdueActive(eq(TODAY), any(Pageable.class)))
            .thenReturn(List.of(enrollments))
            .thenReturn(List.of());
        for (ProgramEnrollment e : enrollments) {
            lenient().when(enrollmentRepository.findById(e.getId())).thenReturn(Optional.of(e));
        }
    }

    @Test
    @DisplayName("an overdue enrolment becomes a PROGRAM_RULE recall with a localized reason")
    void overdueBecomesRecall() {
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        candidates(enrollment);
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), TODAY.minusDays(5))).thenReturn(false);
        when(messageSource.getMessage(eq("care.gap.program.HIV"), any(), any(Locale.class)))
            .thenReturn("VIH");
        when(messageSource.getMessage(eq("care.gap.recall.reason"), any(), any(Locale.class)))
            .thenReturn("Visite du programme VIH en retard");
        when(recallRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = service.traceDefaulters();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<PatientRecall> captor = ArgumentCaptor.forClass(PatientRecall.class);
        verify(recallRepository).saveAndFlush(captor.capture());
        PatientRecall recall = captor.getValue();
        assertThat(recall.getSource()).isEqualTo(RecallSource.PROGRAM_RULE);
        assertThat(recall.getRecallType()).isEqualTo(RecallType.FOLLOW_UP);
        assertThat(recall.getStatus()).isEqualTo(RecallStatus.PENDING);
        assertThat(recall.getDueDate()).isEqualTo(TODAY.minusDays(5));
        assertThat(recall.getReason()).isEqualTo("Visite du programme VIH en retard");
        // The localized display name is interpolated, never the raw enum.
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("care.gap.recall.reason"), args.capture(),
            any(Locale.class));
        assertThat(args.getValue()[0]).isEqualTo("VIH");
    }

    @Test
    @DisplayName("each row runs in its own transaction, so one failure loses exactly one row")
    void perRowTransactions() {
        ProgramEnrollment bad = overdueEnrollment(TODAY.minusDays(3));
        ProgramEnrollment good = overdueEnrollment(TODAY.minusDays(4));
        candidates(bad, good);
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(any(), any()))
            .thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("r");
        when(recallRepository.saveAndFlush(any()))
            .thenThrow(new IllegalStateException("boom"))
            .thenAnswer(inv -> inv.getArgument(0));

        int created = service.traceDefaulters();

        assertThat(created).isEqualTo(1);
        // Two independent transaction executions — the shape the fix exists for.
        verify(transactionTemplate, times(2)).execute(any());
    }

    @Test
    @DisplayName("a visit recorded between the candidate read and the write is seen, not raced")
    void staleVisitedEnrollmentIsSkipped() {
        ProgramEnrollment stale = overdueEnrollment(TODAY.minusDays(5));
        candidates(stale);
        // By the time the per-row transaction reloads it, a visit advanced
        // the expected date into the future.
        ProgramEnrollment fresh = overdueEnrollment(TODAY.minusDays(5));
        fresh.setId(stale.getId());
        fresh.setLastVisitOn(TODAY);
        fresh.setNextExpectedVisit(TODAY.plusDays(30));
        when(enrollmentRepository.findById(stale.getId())).thenReturn(Optional.of(fresh));

        int created = service.traceDefaulters();

        assertThat(created).isZero();
        verify(recallRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an enrolment closed between the candidate read and the write is skipped — DECEASED must never be traced")
    void staleClosedEnrollmentIsSkipped() {
        ProgramEnrollment stale = overdueEnrollment(TODAY.minusDays(5));
        candidates(stale);
        ProgramEnrollment fresh = overdueEnrollment(TODAY.minusDays(5));
        fresh.setId(stale.getId());
        fresh.setStatus(ProgramEnrollmentStatus.DECEASED);
        when(enrollmentRepository.findById(stale.getId())).thenReturn(Optional.of(fresh));

        int created = service.traceDefaulters();

        assertThat(created).isZero();
        verify(recallRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a missed date that raced its way to an existing recall is not traced twice")
    void dedupesByMissedDate() {
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        candidates(enrollment);
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), TODAY.minusDays(5))).thenReturn(true);

        int created = service.traceDefaulters();

        assertThat(created).isZero();
        verify(recallRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a configured grace period shifts the cutoff handed to the candidate query")
    void graceShiftsCutoff() {
        ReflectionTestUtils.setField(service, "graceDays", 7L);
        when(enrollmentRepository.findUntracedOverdueActive(
            eq(TODAY.minusDays(7)), any(Pageable.class))).thenReturn(List.of());

        service.traceDefaulters();

        verify(enrollmentRepository).findUntracedOverdueActive(
            eq(TODAY.minusDays(7)), any(Pageable.class));
    }

    @Test
    @DisplayName("a page that produced nothing ends the sweep instead of re-reading it forever")
    void unproductivePageEndsTheSweep() {
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        // Candidate query would return the same row again and again if the
        // loop kept asking; the exists-check makes the page unproductive.
        when(enrollmentRepository.findUntracedOverdueActive(eq(TODAY), any(Pageable.class)))
            .thenReturn(List.of(enrollment));
        when(enrollmentRepository.findById(enrollment.getId()))
            .thenReturn(Optional.of(enrollment));
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(any(), any()))
            .thenReturn(true);

        int created = service.traceDefaulters();

        assertThat(created).isZero();
        verify(enrollmentRepository, times(1))
            .findUntracedOverdueActive(eq(TODAY), any(Pageable.class));
    }

    @Test
    @DisplayName("a created tracing recall lands in the audit trail as a SYSTEM write")
    void tracingEmitsAudit() {
        ProgramEnrollment enrollment = overdueEnrollment(TODAY.minusDays(5));
        candidates(enrollment);
        when(recallRepository.existsByProgramEnrollment_IdAndDueDate(any(), any()))
            .thenReturn(false);
        when(messageSource.getMessage(anyString(), any(), any(Locale.class))).thenReturn("r");
        when(recallRepository.saveAndFlush(any())).thenAnswer(inv -> {
            PatientRecall r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        service.traceDefaulters();

        ArgumentCaptor<AuditEventRequestDTO> captor =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType())
            .isEqualTo(AuditEventType.PROGRAM_DEFAULTER_TRACED);
        assertThat(captor.getValue().getPatientId())
            .isEqualTo(enrollment.getPatient().getId());
        assertThat(captor.getValue().getUserName()).isEqualTo("SYSTEM");
    }
}
