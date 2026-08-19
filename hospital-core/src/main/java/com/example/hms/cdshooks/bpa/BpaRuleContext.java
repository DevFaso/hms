package com.example.hms.cdshooks.bpa;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;

import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of patient state handed to every {@link BpaRule}.
 * Carries the recent vitals, active problems, and active prescriptions
 * the rules need without forcing each rule to re-query the database.
 *
 * <p>Built by {@link BpaRuleEngine}; never assembled by a rule. Records
 * are public so Mockito spies can verify accessors in tests without
 * accessor reflection probes.
 *
 * @param patient                 the patient whose chart was opened
 * @param hospitalId              hospital scope of the chart-view session
 * @param recentVitals            vital-sign records in chronological-DESC
 *                                order (most recent first), already
 *                                filtered to the engine's lookback window
 * @param activeProblems          patient problems with non-terminal
 *                                status — used by rules to gate or
 *                                suppress advisories (e.g. don't suggest
 *                                a malaria workup if malaria is already
 *                                an active problem and being treated)
 * @param activePrescriptions     non-terminal prescriptions, used by the
 *                                malaria rule to avoid double-dispatching
 *                                an anti-malarial workup
 */
public record BpaRuleContext(
    Patient patient,
    UUID hospitalId,
    List<PatientVitalSign> recentVitals,
    List<PatientProblem> activeProblems,
    List<Prescription> activePrescriptions
) {

    /** Defensive copies + null guards keep the record fully immutable. */
    public BpaRuleContext {
        recentVitals = recentVitals == null ? List.of() : List.copyOf(recentVitals);
        activeProblems = activeProblems == null ? List.of() : List.copyOf(activeProblems);
        activePrescriptions = activePrescriptions == null ? List.of() : List.copyOf(activePrescriptions);
    }

    /** True when the patient context is fully populated. */
    public boolean hasPatient() {
        return patient != null && patient.getId() != null;
    }
}
