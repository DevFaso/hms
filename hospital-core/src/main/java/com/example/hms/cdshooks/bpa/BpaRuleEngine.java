package com.example.hms.cdshooks.bpa;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a {@link BpaRuleContext} for a given patient and runs every
 * registered {@link BpaRule} against it. The result is the concatenated
 * list of {@link CdsCard cards} returned by the rules.
 *
 * <p>Loads recent vitals once (24h lookback covers all three v0 rules:
 * malaria fever 24h, sepsis qSOFA 6h, OB hemorrhage 6h), active problems
 * once, and active prescriptions once — rules are then pure functions of
 * the assembled context. This avoids hidden N+1 lookups inside individual
 * rules and keeps the chart-view load to three queries regardless of how
 * many rules are registered.
 */
@Service
public class BpaRuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(BpaRuleEngine.class);

    /**
     * Vitals lookback for context assembly. The widest rule window in v0
     * is 24h (malaria); narrower windows (qSOFA / OB hemorrhage) filter
     * down inside the rule. Increase here if a future rule needs longer
     * history.
     */
    static final Duration VITALS_LOOKBACK = Duration.ofHours(24);

    /**
     * Page size for the vitals query. We page until either the 24h
     * window is exhausted or {@link #VITALS_MAX_PAGES} pages are loaded —
     * whichever comes first. q15min vitals over 24h yield 96 records, q5min
     * (ICU) yield 288, continuous (q1min) yield 1440, so a single page would
     * silently drop older-but-still-in-window readings on heavily monitored
     * patients.
     */
    private static final int VITALS_PAGE_SIZE = 100;

    /**
     * Hard cap on pages loaded per chart-view to bound the query cost on a
     * pathological patient (e.g. broken capture device producing thousands
     * of records / hour). 20 pages × 100 records = 2000 vitals — covers
     * continuous q1min monitoring for 24h with comfortable headroom.
     */
    private static final int VITALS_MAX_PAGES = 20;

    /**
     * Statuses considered terminal. Anything else (DRAFT, SIGNED,
     * TRANSMITTED, DISPENSED, etc.) counts as a prescription the patient
     * could currently be on.
     */
    private static final Set<PrescriptionStatus> TERMINAL_PRESCRIPTION_STATUSES = EnumSet.of(
        PrescriptionStatus.CANCELLED,
        PrescriptionStatus.DISCONTINUED,
        PrescriptionStatus.PARTNER_REJECTED
    );

    /** Problem statuses that count as "active" for BPA gating. */
    private static final Set<ProblemStatus> ACTIVE_PROBLEM_STATUSES = EnumSet.of(
        ProblemStatus.ACTIVE,
        ProblemStatus.RECURRENCE
    );

    private final List<BpaRule> rules;
    private final PatientVitalSignRepository vitalSignRepository;
    private final PatientProblemRepository problemRepository;
    private final PrescriptionRepository prescriptionRepository;

    public BpaRuleEngine(
        List<BpaRule> rules,
        PatientVitalSignRepository vitalSignRepository,
        PatientProblemRepository problemRepository,
        PrescriptionRepository prescriptionRepository
    ) {
        this.rules = List.copyOf(rules);
        this.vitalSignRepository = vitalSignRepository;
        this.problemRepository = problemRepository;
        this.prescriptionRepository = prescriptionRepository;
    }

    /** Build a context for the given patient; never null. */
    public BpaRuleContext buildContext(Patient patient, UUID hospitalId) {
        if (patient == null || patient.getId() == null) {
            return new BpaRuleContext(null, hospitalId, List.of(), List.of(), List.of());
        }
        UUID patientId = patient.getId();
        return new BpaRuleContext(
            patient,
            hospitalId,
            loadRecentVitals(patientId, hospitalId),
            loadActiveProblems(patientId, hospitalId),
            loadActivePrescriptions(patientId, hospitalId)
        );
    }

    /** Run every registered rule against the context. */
    public List<CdsCard> evaluate(BpaRuleContext context) {
        if (context == null || !context.hasPatient()) return List.of();
        List<CdsCard> cards = new ArrayList<>();
        for (BpaRule rule : rules) {
            try {
                List<CdsCard> ruleCards = rule.evaluate(context);
                if (ruleCards != null) cards.addAll(ruleCards);
            } catch (RuntimeException ex) {
                // Defensive: a buggy rule must not crash the chart load.
                // Log with the throwable so the stack trace lands in
                // production logs and `ex.getMessage()` being null
                // doesn't strip diagnostic context.
                logger.warn("BPA rule {} threw {}: {}", rule.id(),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
            }
        }
        return cards;
    }

    /** Convenience: build context + evaluate. */
    public List<CdsCard> evaluateForPatient(Patient patient, UUID hospitalId) {
        return evaluate(buildContext(patient, hospitalId));
    }

    /* =====================================================================
       Helpers — package-private so tests can verify in isolation.
       ===================================================================== */

    List<PatientVitalSign> loadRecentVitals(UUID patientId, UUID hospitalId) {
        LocalDateTime from = LocalDateTime.now().minus(VITALS_LOOKBACK);
        List<PatientVitalSign> all = new ArrayList<>();
        // Page until either the window is exhausted (last page returned a
        // partial batch) or VITALS_MAX_PAGES is reached. Single exit to
        // satisfy Sonar S135; an empty batch is a partial batch (size 0 <
        // VITALS_PAGE_SIZE) so it terminates the loop after a no-op addAll.
        int pageNumber = 0;
        boolean moreToFetch = true;
        while (moreToFetch && pageNumber < VITALS_MAX_PAGES) {
            Pageable page = PageRequest.of(pageNumber, VITALS_PAGE_SIZE);
            List<PatientVitalSign> batch = vitalSignRepository.findWithinRange(
                patientId, hospitalId, from, null, page);
            all.addAll(batch);
            moreToFetch = batch.size() == VITALS_PAGE_SIZE;
            pageNumber++;
        }
        return all;
    }

    List<PatientProblem> loadActiveProblems(UUID patientId, UUID hospitalId) {
        List<PatientProblem> all = hospitalId != null
            ? problemRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : problemRepository.findByPatient_Id(patientId);
        return all.stream()
            .filter(p -> p.getStatus() == null || ACTIVE_PROBLEM_STATUSES.contains(p.getStatus()))
            .toList();
    }

    List<Prescription> loadActivePrescriptions(UUID patientId, UUID hospitalId) {
        List<Prescription> all = hospitalId != null
            ? prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId)
            : prescriptionRepository.findByPatient_Id(patientId, Pageable.unpaged()).getContent();
        return all.stream()
            .filter(p -> p.getStatus() != null && !TERMINAL_PRESCRIPTION_STATUSES.contains(p.getStatus()))
            .toList();
    }
}
