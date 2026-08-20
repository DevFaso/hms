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
 * <p>National-ID alias is resolved ONCE per inbound request and passed
 * into {@link #score} — without this single-resolve pattern, a search
 * over N candidates would trigger N alias lookups (the N+1 path
 * Copilot flagged in PR copilot-review).
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
    private final com.example.hms.utility.RoleValidator roleValidator;

    public EmpiProbabilisticMatcher(
        EmpiProbabilisticProperties properties,
        PatientRepository patientRepository,
        EmpiService empiService,
        com.example.hms.utility.RoleValidator roleValidator
    ) {
        this.properties = properties;
        this.patientRepository = patientRepository;
        this.empiService = empiService;
        this.roleValidator = roleValidator;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public List<EmpiCandidateMatchDTO> findCandidates(EmpiCandidateQueryDTO query) {
        if (!properties.isEnabled() || query == null) return Collections.emptyList();

        UUID nationalIdMatch = resolveNationalIdAliasOnce(query);
        List<Patient> candidates = collectCandidates(query, nationalIdMatch);

        // ── Tenant isolation (empi-identity skill: "scope the matcher to a
        // single hospital's PatientHospitalRegistration set"). Null scope =
        // super-admin unscoped; scoped callers never see cross-hospital
        // demographics in the candidate list. ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            candidates = candidates.stream()
                .filter(candidate -> candidate.isRegisteredInHospital(activeHospitalId))
                .toList();
        }
        if (candidates.isEmpty()) return Collections.emptyList();

        return rankCandidates(candidates, query, nationalIdMatch);
    }

    /**
     * Resolve {@code query.nationalId()} to a single patient-id via
     * the EMPI alias index, once per request. Returns null when the
     * inbound query has no national-id or the alias is unknown.
     */
    private UUID resolveNationalIdAliasOnce(EmpiCandidateQueryDTO query) {
        if (!isPresent(query.nationalId())) return null;
        return empiService
            .findIdentityByAlias(EmpiAliasType.NATIONAL_ID, query.nationalId().trim())
            .map(id -> id.getPatientId())
            .orElse(null);
    }

    private List<Patient> collectCandidates(EmpiCandidateQueryDTO query, UUID nationalIdMatch) {
        Set<UUID> seen = new HashSet<>();
        List<Patient> candidates = new ArrayList<>();
        addNamePrefixBlock(query, seen, candidates);
        addNationalIdAliasBlock(nationalIdMatch, seen, candidates);
        return candidates;
    }

    /**
     * Block 1: name-prefix candidates. Coarse — the audit-set follow-on
     * will tighten to last-name n-gram + DOB-year blocking once ROC
     * analysis is available.
     */
    private void addNamePrefixBlock(
        EmpiCandidateQueryDTO query, Set<UUID> seen, List<Patient> candidates
    ) {
        if (!isPresent(query.firstName()) && !isPresent(query.lastName())) return;
        String first = isPresent(query.firstName()) ? query.firstName().trim() : "";
        String last = isPresent(query.lastName()) ? query.lastName().trim() : "";
        List<Patient> byName = patientRepository
            .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(first, last);
        for (Patient p : byName) {
            addUnique(p, seen, candidates);
        }
    }

    /**
     * Block 2: exact national-ID match via the pre-resolved alias.
     * Even when the alias resolves cleanly we still send the patient
     * through the scorer so the receptionist UI can render the
     * per-field breakdown (name disagrees → operator sees that and
     * pauses instead of auto-confirming).
     */
    private void addNationalIdAliasBlock(
        UUID nationalIdMatch, Set<UUID> seen, List<Patient> candidates
    ) {
        if (nationalIdMatch == null) return;
        patientRepository.findById(nationalIdMatch)
            .ifPresent(p -> addUnique(p, seen, candidates));
    }

    private static void addUnique(Patient p, Set<UUID> seen, List<Patient> candidates) {
        if (p != null && p.getId() != null && seen.add(p.getId())) {
            candidates.add(p);
        }
    }

    private List<EmpiCandidateMatchDTO> rankCandidates(
        List<Patient> candidates, EmpiCandidateQueryDTO query, UUID nationalIdMatch
    ) {
        double minScore = properties.getMinScore();
        List<EmpiCandidateMatchDTO> scored = new ArrayList<>(candidates.size());
        for (Patient p : candidates) {
            EmpiCandidateMatchDTO dto = score(p, query, nationalIdMatch);
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
    EmpiCandidateMatchDTO score(Patient candidate, EmpiCandidateQueryDTO query, UUID nationalIdMatch) {
        double name = EmpiSimilarity.combinedNameSimilarity(
            query.firstName(), query.lastName(),
            candidate.getFirstName(), candidate.getLastName());
        double dob = EmpiSimilarity.dobSimilarity(query.dateOfBirth(), candidate.getDateOfBirth());
        double sex = EmpiSimilarity.sexSimilarity(query.sex(), candidate.getGender());
        // National-ID similarity uses the alias resolved ONCE per request
        // (see resolveNationalIdAliasOnce). Same patient id ⇒ 1.0,
        // otherwise 0.0 — the alias store is the source of truth, not
        // the per-row similarity math.
        double nid = (nationalIdMatch != null
            && candidate.getId() != null
            && candidate.getId().equals(nationalIdMatch)) ? 1.0 : 0.0;

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
