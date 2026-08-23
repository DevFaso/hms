package com.example.hms.service.reporting;

import com.example.hms.enums.ReportRunStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.platform.ReportDefinition;
import com.example.hms.model.platform.ReportRun;
import com.example.hms.repository.ReportDefinitionRepository;
import com.example.hms.repository.ReportRunRepository;
import com.example.hms.service.EmailService;
import com.example.hms.service.reporting.ReportGenerationService.GeneratedReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The scheduled-report engine (P3 #25a): sweeps active definitions and
 * emits the PRIOR closed period exactly once.
 *
 * <p><strong>Exactly-once via insert-first.</strong> The run row is
 * inserted (and flushed) as GENERATING before any generation happens;
 * the UNIQUE (definition, period) constraint makes that insert the
 * claim, so a second sweep instance racing on the same period hits a
 * constraint violation instead of sending a duplicate email. This is
 * deliberately NOT the reminder-stamp idiom — a check-then-act stamp
 * cannot survive two instances, and this codebase runs without ShedLock.
 *
 * <p>Deliberately no outer transaction on the sweep: the claim must
 * COMMIT before the (slow) generation starts, or the claim window would
 * stay open until the email finished.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledReportService {

    private final ReportDefinitionRepository definitionRepository;
    private final ReportRunRepository runRepository;
    private final ReportGenerationService generationService;
    private final EmailService emailService;

    /** @return number of reports actually emitted on this pass */
    public int sweep() {
        int emitted = 0;
        for (ReportDefinition definition : definitionRepository.findByActiveTrue()) {
            try {
                String token = ReportGenerationService.priorPeriodToken(
                    definition.getPeriod(), LocalDate.now());
                if (runRepository.findByDefinition_IdAndPeriodToken(
                        definition.getId(), token).isPresent()) {
                    continue; // this period already claimed — the normal steady state
                }
                ReportRun run = execute(definition, token, false);
                if (run != null && run.getStatus() == ReportRunStatus.SUCCEEDED) {
                    emitted++;
                }
            } catch (RuntimeException ex) {
                // One bad definition never stalls the rest of the sweep.
                log.warn("Scheduled report failed for definition {}: {}",
                    definition.getId(), ex.getMessage(), ex);
            }
        }
        return emitted;
    }

    /**
     * Generate + email one period of one definition.
     *
     * <p>Manual mode may RETRY a FAILED period (the run row is reused);
     * a SUCCEEDED or in-flight period is refused — re-sending an
     * already-delivered report needs a human decision, not an endpoint
     * that silently duplicates email.
     */
    public ReportRun execute(ReportDefinition definition, String periodToken, boolean manual) {
        ReportRun run = claim(definition, periodToken, manual);
        if (run == null) {
            return null;
        }
        try {
            GeneratedReport report = generationService.generate(definition, periodToken);
            emailService.sendWithAttachment(
                definition.recipientList(), List.of(), List.of(),
                "[HMS] " + definition.getName() + " — " + periodToken,
                "<p>Scheduled report <strong>" + definition.getName()
                    + "</strong> for period <strong>" + periodToken
                    + "</strong> is attached.</p><p>"
                    + report.rowCount() + " data row(s). Aggregate counts only — no patient data.</p>",
                report.content(), report.filename(), "text/csv");
            run.setStatus(ReportRunStatus.SUCCEEDED);
            run.setRowCount(report.rowCount());
            run.setGeneratedAt(LocalDateTime.now());
            return runRepository.save(run);
        } catch (RuntimeException ex) {
            run.setStatus(ReportRunStatus.FAILED);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            run.setErrorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
            runRepository.save(run);
            if (manual) {
                throw ex; // the operator asked; surface the refusal verbatim
            }
            log.warn("Report generation failed for definition {} period {}: {}",
                definition.getId(), periodToken, message);
            return run;
        }
    }

    private ReportRun claim(ReportDefinition definition, String periodToken, boolean manual) {
        try {
            return runRepository.saveAndFlush(ReportRun.builder()
                .definition(definition)
                .periodToken(periodToken)
                .status(ReportRunStatus.GENERATING)
                .build());
        } catch (DataIntegrityViolationException ex) {
            Optional<ReportRun> existing = runRepository
                .findByDefinition_IdAndPeriodToken(definition.getId(), periodToken);
            if (manual && existing.isPresent()
                && existing.get().getStatus() == ReportRunStatus.FAILED) {
                ReportRun retry = existing.get();
                retry.setStatus(ReportRunStatus.GENERATING);
                retry.setErrorMessage(null);
                return runRepository.saveAndFlush(retry);
            }
            if (manual) {
                throw new BusinessException("Report for period " + periodToken
                    + " was already generated"
                    + existing.map(r -> " (" + r.getStatus() + ")").orElse("")
                    + ". Duplicate sends need a new period.");
            }
            return null; // another instance claimed it — fine
        }
    }
}
