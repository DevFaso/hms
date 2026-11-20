package com.example.hms.service.recordaccess;

import com.example.hms.enums.LabTestDefinitionApprovalStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.repository.LabTestDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * QA D2730 (r65, High): the one rule for what a proxy (a caregiver holding a
 * family-access grant) may read of the grantor's record.
 *
 * <p><b>A proxy never receives a row whose effective sensitivity category is
 * set</b> (HIV, behavioural health, substance use, reproductive health). The
 * category is the codebase's existing one ({@link SensitivityClassifier}): the
 * row's explicit tag, else its visit's, else the department default. A lab
 * result also carries its test's tag, when the hospital set one; a problem
 * follows the visit it was recorded in; a vital sign, which names no visit,
 * follows the sensitive visit it was taken during.
 *
 * <p>No grant scope covers sensitive content today: VIEW_RECORDS, the other
 * VIEW_* scopes and ALL name the ordinary record only. So a proxy gets none of
 * it, whatever the grant says, until a scope that explicitly covers it exists.
 * The patient's own reads never come through here.
 *
 * <p>Every proxy read applies this filter; {@code ProxyReadSensitivityGuardTest}
 * fails when a new one does not.
 */
@Component
@RequiredArgsConstructor
public class ProxyRecordFilter {

    private final SensitivityClassifier sensitivityClassifier;
    private final LabTestDefinitionRepository labTestDefinitionRepository;

    /** What one test's live definition says: whether there is one, and its tag. */
    private record LiveTestTag(boolean hasLiveVersion, SensitivityCategory category) {
    }

    /** The rule itself: only an untagged row reaches a proxy. */
    public boolean admits(SensitivityCategory effectiveCategory) {
        return effectiveCategory == null;
    }

    /** A visit, by its explicit tag or its department's default. */
    public boolean admits(Encounter encounter) {
        return admits(sensitivityClassifier.effectiveCategory(encounter));
    }

    /** A result inherits its order's visit and its test's tag. */
    public boolean admits(LabResult result) {
        return result == null || admits(result.getLabOrder());
    }

    public boolean admits(LabOrder order) {
        return admits(order, new HashMap<>());
    }

    /**
     * One read's filter over lab results. The tag of each test is resolved
     * once for the whole read, however many results of it the patient has.
     */
    public Predicate<LabResult> labResults() {
        Map<String, LiveTestTag> testTags = new HashMap<>();
        return result -> result == null || admits(result.getLabOrder(), testTags);
    }

    private boolean admits(LabOrder order, Map<String, LiveTestTag> testTags) {
        if (order == null) {
            return true;
        }
        if (!admits(order.getEncounter())) {
            return false;
        }
        return admits(testCategory(order.getLabTestDefinition(), testTags));
    }

    /**
     * The category of a test, across its versions. A new version retires the
     * definition it replaces, and a retired definition is read-only: read on
     * the exact row an order used, the results of version 1 stayed visible
     * after version 2 was tagged. So the test's <em>live</em> definition
     * decides - the ACTIVE one with the same code in the same catalogue (the
     * hospital's, or the platform's) - for every result of that test, old and
     * new. A test with no live definition left keeps the tag of the row the
     * order used.
     */
    private SensitivityCategory testCategory(LabTestDefinition definition, Map<String, LiveTestTag> testTags) {
        if (definition == null) {
            return null;
        }
        String code = definition.getTestCode();
        if (code == null || code.isBlank()) {
            return definition.getSensitivityCategory();
        }
        Hospital hospital = definition.getHospital();
        UUID hospitalId = hospital == null ? null : hospital.getId();
        LiveTestTag live = testTags.computeIfAbsent(hospitalId + "|" + code.trim().toUpperCase(Locale.ROOT),
            key -> liveTagOf(code.trim(), hospitalId));
        return live.hasLiveVersion() ? live.category() : definition.getSensitivityCategory();
    }

    private LiveTestTag liveTagOf(String code, UUID hospitalId) {
        List<LabTestDefinition> versions = hospitalId == null
            ? labTestDefinitionRepository.findLiveGlobalByCode(code)
            : labTestDefinitionRepository.findLiveByCodeAtHospital(code, hospitalId);
        List<LabTestDefinition> live = versions == null ? List.of() : versions.stream()
            .filter(version -> version.getApprovalStatus() == LabTestDefinitionApprovalStatus.ACTIVE)
            .toList();
        return new LiveTestTag(!live.isEmpty(), live.stream()
            .map(LabTestDefinition::getSensitivityCategory)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null));
    }

    /** A prescription written in a sensitive visit is sensitive. */
    public boolean admits(Prescription prescription) {
        return prescription == null || admits(prescription.getEncounter());
    }

    /** A problem by its own tag, else by the visit it was recorded in. */
    public boolean admits(PatientProblem problem) {
        return admits(sensitivityClassifier.effectiveCategory(problem));
    }

    public boolean admits(PatientImmunization immunization) {
        return immunization == null || admits(immunization.getEncounter());
    }

    /** An invoice names the visit's services; it follows its visit. */
    public boolean admits(BillingInvoice invoice) {
        return invoice == null || admits(invoice.getEncounter());
    }

    /**
     * An appointment reveals its department, so a department that defaults to
     * a sensitive category withholds it. The visit it opened, when there is
     * one, is judged by the caller (it is the owning side of the link).
     */
    public boolean admits(Appointment appointment) {
        return appointment == null
            || admits(sensitivityClassifier.effectiveCategory(null, appointment.getDepartment()));
    }

    /** Of a patient's visits, the ones a proxy may not read. */
    public List<Encounter> sensitiveAmong(Collection<Encounter> visits) {
        return visits == null ? List.of() : visits.stream().filter(visit -> !admits(visit)).toList();
    }

    /**
     * A vital sign names no visit, so it cannot carry one's tag. It is
     * withheld when it was taken at the hospital of a sensitive visit, on a
     * day that visit covers (its first day to its checkout day; to today for
     * a visit still open): a weight or a blood pressure taken in the HIV
     * clinic that morning says the patient was there.
     *
     * @param sensitiveVisits the patient's sensitive visits ({@link #sensitiveAmong})
     */
    public boolean admits(PatientVitalSign vital, Collection<Encounter> sensitiveVisits) {
        if (vital == null || sensitiveVisits == null || sensitiveVisits.isEmpty()) {
            return true;
        }
        LocalDateTime recordedAt = vital.getRecordedAt();
        if (recordedAt == null) {
            // No time to place it by: withheld, since the patient has sensitive visits.
            return false;
        }
        LocalDate day = recordedAt.toLocalDate();
        UUID vitalHospitalId = vital.getHospital() == null ? null : vital.getHospital().getId();
        return sensitiveVisits.stream().noneMatch(visit -> sameHospital(vitalHospitalId, visit) && covers(visit, day));
    }

    private static boolean sameHospital(UUID vitalHospitalId, Encounter visit) {
        UUID visitHospitalId = visit.getHospital() == null ? null : visit.getHospital().getId();
        // An unknown hospital on either side cannot rule the visit out.
        return vitalHospitalId == null || visitHospitalId == null || vitalHospitalId.equals(visitHospitalId);
    }

    private static boolean covers(Encounter visit, LocalDate day) {
        if (visit.getEncounterDate() == null) {
            return true;
        }
        LocalDate first = visit.getEncounterDate().toLocalDate();
        LocalDate last;
        if (visit.getCheckoutTimestamp() != null) {
            last = visit.getCheckoutTimestamp().toLocalDate();
        } else if (visit.getStatus() != null && visit.getStatus().isClosed()) {
            last = first;
        } else {
            last = LocalDate.now();
        }
        LocalDate end = last.compareTo(first) < 0 ? first : last;
        return day.compareTo(first) >= 0 && day.compareTo(end) <= 0;
    }
}
