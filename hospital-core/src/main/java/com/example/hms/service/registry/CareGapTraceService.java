package com.example.hms.service.registry;

import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Defaulter tracing (Tier 2 item 36): turns overdue programme enrolments
 * into patient recalls.
 *
 * <p>The care gap IS the row item 35 already maintains — an ACTIVE
 * enrolment whose {@code nextExpectedVisit} has passed — and the tracing
 * apparatus IS the recall pipeline that already exists: the desk worklist,
 * {@link com.example.hms.service.scheduling.RecallReminderService}'s
 * notification sweep with its preference and SMS-guard handling, and the
 * booking flow. This service only writes the recall row; everything
 * downstream is #476's machinery, untouched.
 *
 * <p><b>One recall per missed expected-visit date</b>, enforced twice: the
 * existence check here and V147's partial unique index underneath it. A
 * recall the desk closed without a visit being recorded is NOT recreated
 * for the same date — the sweep must never fight the desk. Recording a
 * visit advances the expected date, so the next miss is a new date and gets
 * a fresh recall.
 *
 * <p><b>No grace period by default.</b> How long after a missed visit a
 * programme starts tracing is programme policy; any non-zero default here
 * would be this codebase inventing it (the item-35 cadence rule again).
 * Zero means "overdue is overdue"; a facility whose protocol waits sets
 * {@code hms.care-gaps.grace-days}.
 */
@Slf4j
@Service
public class CareGapTraceService {

    private final ProgramEnrollmentRepository enrollmentRepository;
    private final PatientRecallRepository recallRepository;
    private final MessageSource messageSource;
    private final Clock clock;

    @Value("${hms.care-gaps.grace-days:0}")
    private long graceDays;

    /** A sweep has no request locale — explicit config, the recall-sweep default. */
    @Value("${hms.scheduling.outreach.locale:fr}")
    private String outreachLocale;

    public CareGapTraceService(ProgramEnrollmentRepository enrollmentRepository,
                               PatientRecallRepository recallRepository,
                               MessageSource messageSource,
                               Clock clock) {
        this.enrollmentRepository = enrollmentRepository;
        this.recallRepository = recallRepository;
        this.messageSource = messageSource;
        this.clock = clock;
    }

    /** @return number of tracing recalls created */
    @Transactional
    public int traceDefaulters() {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(graceDays);
        List<ProgramEnrollment> overdue = enrollmentRepository.findOverdueActive(cutoff);
        int created = 0;
        for (ProgramEnrollment enrollment : overdue) {
            try {
                if (traceOne(enrollment)) {
                    created++;
                }
            } catch (RuntimeException ex) {
                // One bad row must not stop the rest of the cohort.
                log.warn("Care-gap tracing failed for enrolment {}: {}",
                    enrollment.getId(), ex.getMessage());
            }
        }
        if (created > 0) {
            log.info("Care-gap sweep: {} tracing recall(s) created from {} overdue enrolment(s)",
                created, overdue.size());
        }
        return created;
    }

    private boolean traceOne(ProgramEnrollment enrollment) {
        LocalDate missedDate = enrollment.getNextExpectedVisit();
        if (recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), missedDate)) {
            return false;
        }
        Locale locale = Locale.forLanguageTag(outreachLocale);
        // The desk-facing reason names the programme; the patient-facing SMS
        // downstream deliberately does not (untrusted channel — the recall
        // sweep's own rule). Programme membership never leaves the building.
        String reason = messageSource.getMessage("care.gap.recall.reason",
            new Object[]{enrollment.getProgram().name(), missedDate}, locale);
        recallRepository.save(PatientRecall.builder()
            .patient(enrollment.getPatient())
            .hospital(enrollment.getHospital())
            .programEnrollment(enrollment)
            .recallType(RecallType.FOLLOW_UP)
            .status(RecallStatus.PENDING)
            .source(RecallSource.PROGRAM_RULE)
            .dueDate(missedDate)
            .reason(reason)
            .build());
        return true;
    }
}
