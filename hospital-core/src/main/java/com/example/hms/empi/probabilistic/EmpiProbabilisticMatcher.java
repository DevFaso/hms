package com.example.hms.empi.probabilistic;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Probabilistic EMPI matcher (roadmap row 25, v1.1 / Patient Identity).
 *
 * <p>Foundation pass + row-25 follow-on scorer body. Scores an inbound
 * draft identity against the candidate Patient set using a weighted-sum
 * Fellegi-Sunter approximation:
 *
 * <pre>
 *   score = 0.40 × combinedNameSimilarity(first, last)
 *         + 0.25 × dobSimilarity(dateOfBirth)
 *         + 0.10 × sexSimilarity(sex)
 *         + 0.25 × nationalIdSimilarity(nationalId)
 * </pre>
 *
 * <p>The four weights sum to 1.0; per-field similarities live in
 * {@link EmpiSimilarity}. Candidates whose composite score is below
 * {@link EmpiProbabilisticProperties#getMinScore()} are dropped; the
 * remainder are sorted desc and truncated to
 * {@link EmpiProbabilisticProperties#getMaxCandidates()}.
 *
 * <p>Candidate generation today is a coarse name-prefix scan via
 * {@link PatientRepository#findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase}
 * + an exact national-ID lookup via the EMPI alias index. A blocking
 * pass keyed on (last-name n-gram + DOB-year) is the named-but-deferred
 * follow-on once the labelled audit set is in place to tune blocking
 * recall.
 *
 * <p>Flag-off (default): {@link #findCandidates} returns an empty list
 * immediately — preserves the foundation-pass contract every existing
 * row-25 IT pins.
 */
@Service
public class EmpiProbabilisticMatcher {

    // Weights are tuned starting points; the labelled audit set is the
    // named follow-on input that re-fits these via ROC analysis. They
    // sum to 1.0 so the composite score stays in [0, 1].
    private static final double W_NAME = 0.40;
    private static final double W_DOB = 0.25;
    private static final double W_SEX = 0.10;
    private static final double W_NATIONAL_ID = 0.25;

    private final EmpiProbabilisticProperties properties;
    private final PatientRepository patientRepository;
    private final EmpiService empiService;

    public EmpiProbabilisticMatcher(
        EmpiProbabilisticProperties properties,
        PatientRepository patientRepository,
        EmpiService empiService
    ) {
        this.properties = properties;
        this.patientRepository = patientRepository;
        this.empiService = empiService;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public List<EmpiCandidateMatchDTO> findCandidates(EmpiCandidateQueryDTO query) {
        if (!properties.isEnabled()) return Collections.emptyList();
        if (query == null) return Collections.emptyList();

        Set<UUID> seen = new HashSet<>();
        List<Patient> candidates = new ArrayList<>();

        // Block 1: name-prefix candidates. Coarse — the audit-set
        // follow-on will tighten to last-name n-gram + DOB-year
        // blocking once ROC analysis is available.
        if (isPresent(query.firstName()) || isPresent(query.lastName())) {
            String first = isPresent(query.firstName()) ? query.firstName().trim() : "";
            String last = isPresent(query.lastName()) ? query.lastName().trim() : "";
            List<Patient> byName = patientRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(first, last);
            for (Patient p : byName) {
                if (p.getId() != null && seen.add(p.getId())) {
                    candidates.add(p);
                }
            }
        }

        // Block 2: exact national-ID match via EMPI alias. Even when the
        // alias resolves cleanly we still send the patient through the
        // scorer so the receptionist UI can render the per-field
        // breakdown (name disagrees → operator sees that and pauses
        // instead of auto-confirming).
        if (isPresent(query.nationalId())) {
            Optional<UUID> patientIdOpt = empiService
                .findIdentityByAlias(EmpiAliasType.NATIONAL_ID, query.nationalId().trim())
                .map(id -> id.getPatientId());
            patientIdOpt.flatMap(patientRepository::findById)
                .ifPresent(p -> {
                    if (p.getId() != null && seen.add(p.getId())) {
                        candidates.add(p);
                    }
                });
        }

        if (candidates.isEmpty()) return Collections.emptyList();

        List<EmpiCandidateMatchDTO> scored = new ArrayList<>(candidates.size());
        double minScore = properties.getMinScore();
        for (Patient p : candidates) {
            EmpiCandidateMatchDTO dto = score(p, query);
            if (dto.score() >= minScore) {
                scored.add(dto);
            }
        }

        scored.sort(Comparator.comparingDouble(EmpiCandidateMatchDTO::score).reversed());
        int max = Math.max(0, properties.getMaxCandidates());
        if (scored.size() > max) {
            return Collections.unmodifiableList(scored.subList(0, max));
        }
        return Collections.unmodifiableList(scored);
    }

    /**
     * Score one candidate Patient against the inbound draft. The
     * per-field booleans on the returned DTO reflect "matched at all"
     * (similarity > 0) so the receptionist UI can render the
     * "name agrees, sex disagrees" breakdown. The numeric score is
     * the weighted-sum composite rounded to three decimals.
     */
    EmpiCandidateMatchDTO score(Patient candidate, EmpiCandidateQueryDTO query) {
        double name = EmpiSimilarity.combinedNameSimilarity(
            query.firstName(), query.lastName(),
            candidate.getFirstName(), candidate.getLastName());
        double dob = EmpiSimilarity.dobSimilarity(query.dateOfBirth(), candidate.getDateOfBirth());
        double sex = EmpiSimilarity.sexSimilarity(query.sex(), candidate.getGender());
        // National-ID is sourced from the EMPI alias index — we treat
        // "candidate has the same NATIONAL_ID alias as the query" as
        // similarity 1.0; absent alias → 0.0. Keeps the scoring math
        // out of the alias-store details.
        double nid = 0.0;
        if (isPresent(query.nationalId()) && candidate.getId() != null) {
            nid = empiService
                .findIdentityByAlias(EmpiAliasType.NATIONAL_ID, query.nationalId().trim())
                .map(id -> candidate.getId().equals(id.getPatientId()) ? 1.0 : 0.0)
                .orElse(0.0);
        }

        double composite =
            W_NAME * name + W_DOB * dob + W_SEX * sex + W_NATIONAL_ID * nid;

        return new EmpiCandidateMatchDTO(
            candidate.getId(),
            combineDisplayName(candidate),
            roundToThree(composite),
            name > 0.0,
            dob > 0.0,
            sex > 0.0,
            nid > 0.0
        );
    }

    private static String combineDisplayName(Patient p) {
        String f = p.getFirstName() == null ? "" : p.getFirstName().trim();
        String l = p.getLastName() == null ? "" : p.getLastName().trim();
        if (f.isEmpty() && l.isEmpty()) return p.getId() == null ? "" : p.getId().toString();
        if (f.isEmpty()) return l;
        if (l.isEmpty()) return f;
        return f + " " + l;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static double roundToThree(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
