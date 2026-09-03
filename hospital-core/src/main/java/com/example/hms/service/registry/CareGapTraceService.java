package com.example.hms.service.registry;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ProgramEnrollmentStatus;
import com.example.hms.enums.RecallSource;
import com.example.hms.enums.RecallStatus;
import com.example.hms.enums.RecallType;
import com.example.hms.model.ProgramEnrollment;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.ProgramEnrollmentRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.AuditEventLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
 * <p><b>Each enrolment commits in its own transaction.</b> The first cut
 * wrapped the whole sweep in one — which silently defeated its own per-row
 * catch blocks: saves flush at commit, after the loop, so one bad row could
 * roll back every recall the night had built. Now a failure loses exactly
 * one row, and {@code saveAndFlush} surfaces it inside the try that owns it.
 *
 * <p><b>Staleness recheck.</b> A visit can be recorded (or the enrolment
 * closed) between the sweep's candidate read and its write; the lifecycle
 * interlocks in {@link ProgramEnrollmentService} run at that moment and
 * would find no recall to resolve yet. So each per-row transaction reloads
 * the enrolment and re-verifies ACTIVE + still-overdue before inserting.
 * V147's unique index remains the last-resort race guard underneath.
 *
 * <p><b>One recall per missed expected-visit date</b> — enforced in the
 * candidate query itself (NOT EXISTS), so historical defaulters whose
 * recalls were closed do not make the sweep an unbounded nightly re-scan,
 * and re-checked per row inside the transaction.
 *
 * <p><b>No grace period by default.</b> How long after a missed visit a
 * programme starts tracing is programme policy; any non-zero default here
 * would be this codebase inventing it (the item-35 cadence rule again).
 */
@Slf4j
@Service
public class CareGapTraceService {

    /** Candidates are processed in bounded pages, never one unbounded read. */
    static final int SWEEP_PAGE_SIZE = 200;

    private final ProgramEnrollmentRepository enrollmentRepository;
    private final PatientRecallRepository recallRepository;
    private final MessageSource messageSource;
    private final AuditEventLogService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Value("${hms.care-gaps.grace-days:0}")
    private long graceDays;

    /** A sweep has no request locale — explicit config, the recall-sweep default. */
    @Value("${hms.scheduling.outreach.locale:fr}")
    private String outreachLocale;

    public CareGapTraceService(ProgramEnrollmentRepository enrollmentRepository,
                               PatientRecallRepository recallRepository,
                               MessageSource messageSource,
                               AuditEventLogService auditService,
                               TransactionTemplate transactionTemplate,
                               Clock clock) {
        this.enrollmentRepository = enrollmentRepository;
        this.recallRepository = recallRepository;
        this.messageSource = messageSource;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * NOT transactional — see the class doc; each row commits alone.
     *
     * @return number of tracing recalls created
     */
    public int traceDefaulters() {
        LocalDate cutoff = utcToday().minusDays(graceDays);
        int created = 0;
        List<ProgramEnrollment> page;
        do {
            // Always page 0: every created recall removes its enrolment from
            // the NOT EXISTS candidate set, so the frontier advances by
            // consumption; advancing the page number instead would skip rows.
            page = enrollmentRepository.findUntracedOverdueActive(
                cutoff, PageRequest.of(0, SWEEP_PAGE_SIZE));
            int createdThisPage = 0;
            for (ProgramEnrollment candidate : page) {
                try {
                    Boolean traced = transactionTemplate.execute(
                        tx -> traceOne(candidate.getId(), cutoff));
                    if (Boolean.TRUE.equals(traced)) {
                        created++;
                        createdThisPage++;
                    }
                } catch (RuntimeException ex) {
                    // One bad row must not stop the rest of the cohort — and
                    // with per-row transactions it genuinely cannot.
                    log.warn("Care-gap tracing failed for enrolment {}: {}",
                        candidate.getId(), ex.getMessage());
                }
            }
            // A page that produced nothing cannot shrink the candidate set;
            // reading again would return the same rows forever.
            if (createdThisPage == 0) {
                break;
            }
        } while (page.size() == SWEEP_PAGE_SIZE);
        if (created > 0) {
            log.info("Care-gap sweep: {} tracing recall(s) created", created);
        }
        return created;
    }

    /**
     * Runs inside its own transaction; reloads the row so a visit recorded
     * (or an enrolment closed) since the candidate read is seen, not raced.
     */
    private boolean traceOne(UUID enrollmentId, LocalDate cutoff) {
        ProgramEnrollment enrollment = enrollmentRepository.findById(enrollmentId).orElse(null);
        if (enrollment == null || enrollment.getStatus() != ProgramEnrollmentStatus.ACTIVE) {
            // Closed (or purged) since the candidate read — the lifecycle
            // interlock owns this enrolment's recalls now, not the sweep.
            return false;
        }
        LocalDate missedDate = enrollment.getNextExpectedVisit();
        if (missedDate == null || !missedDate.isBefore(cutoff)) {
            // A visit was recorded since the candidate read and the expected
            // date moved forward — there is no gap any more.
            return false;
        }
        if (recallRepository.existsByProgramEnrollment_IdAndDueDate(
            enrollment.getId(), missedDate)) {
            return false;
        }
        Locale locale = Locale.forLanguageTag(outreachLocale);
        // Localized programme name, not the raw enum: "TB" inside a French
        // sentence is mixed text the desk should never see. The desk-facing
        // reason names the programme; the patient-facing SMS downstream
        // deliberately does not (untrusted channel — the recall sweep's own
        // rule). Programme membership never leaves the building.
        String programName = messageSource.getMessage(
            "care.gap.program." + enrollment.getProgram().name(), null, locale);
        String reason = messageSource.getMessage("care.gap.recall.reason",
            new Object[]{programName, missedDate}, locale);
        PatientRecall saved = recallRepository.saveAndFlush(PatientRecall.builder()
            .patient(enrollment.getPatient())
            .hospital(enrollment.getHospital())
            .programEnrollment(enrollment)
            .recallType(RecallType.FOLLOW_UP)
            .status(RecallStatus.PENDING)
            .source(RecallSource.PROGRAM_RULE)
            .dueDate(missedDate)
            .reason(reason)
            .build());
        emitAudit(saved, enrollment);
        return true;
    }

    /**
     * UTC on purpose: the scheduler is pinned to UTC, and the injected
     * production clock is zone-of-host — a Ouagadougou host happens to BE
     * UTC, but the sweep must not start tracing a day early or late the day
     * that stops being true.
     */
    private LocalDate utcToday() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Best-effort, system actor: no human made this write. */
    private void emitAudit(PatientRecall recall, ProgramEnrollment enrollment) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PROGRAM_DEFAULTER_TRACED)
                .status(AuditStatus.SUCCESS)
                .entityType("PatientRecall")
                .resourceId(recall.getId() != null ? recall.getId().toString() : null)
                .userId(null)
                .userName("SYSTEM")
                .hospitalName(enrollment.getHospital() != null
                    ? enrollment.getHospital().getName() : null)
                .patientId(enrollment.getPatient() != null
                    ? enrollment.getPatient().getId() : null)
                .eventDescription("Care-gap sweep created a tracing recall for "
                    + enrollment.getProgram() + " (visit expected "
                    + enrollment.getNextExpectedVisit() + ")")
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit audit for tracing recall {}: {}",
                recall.getId(), ex.getMessage());
        }
    }
}
