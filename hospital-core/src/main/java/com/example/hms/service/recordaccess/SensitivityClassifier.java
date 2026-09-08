package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.model.Admission;
import com.example.hms.model.Consultation;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.NursingNote;
import com.example.hms.model.PatientProblem;

/**
 * E8 #51 — what category does this clinical row carry, and may it leave the
 * hospital that recorded it?
 *
 * <p>Two rules, and they are the whole of it:
 *
 * <ol>
 *   <li><b>Effective category</b> = the row's explicit tag, or failing that
 *       the department's default. The row always wins, so a clinician can
 *       mark one encounter in a general clinic as behavioural health, or
 *       clear the default on a row that does not deserve it.</li>
 *   <li><b>Default-withhold</b>: a row with any effective category does
 *       <em>not</em> travel to another hospital on the treatment presumption.
 *       Release is a later pass, through ROI — the existing vehicle for a
 *       patient-authorised disclosure.</li>
 * </ol>
 *
 * <p>Nothing here reads free text. The predecessor
 * ({@code PatientServiceImpl.isSensitiveEncounter} and friends) substring-matched
 * English keywords against notes and department names at read time, which
 * matches nothing a French-speaking clinician writes and makes the same row
 * sensitive or not depending on wording. That heuristic still drives the
 * intra-hospital doctor-chart toggle and is removed in #49.
 */
public interface SensitivityClassifier {

    /** The core rule: explicit tag, else the department default, else none. */
    SensitivityCategory effectiveCategory(SensitivityCategory explicit, Department department);

    SensitivityCategory effectiveCategory(Encounter encounter);

    SensitivityCategory effectiveCategory(Admission admission);

    /** Consultations carry no department of their own; theirs comes via the encounter. */
    SensitivityCategory effectiveCategory(Consultation consultation);

    /** Problems attach to patient and hospital only — explicit tag or nothing. */
    SensitivityCategory effectiveCategory(PatientProblem problem);

    /** Nursing notes attach to patient and hospital only — explicit tag or nothing. */
    SensitivityCategory effectiveCategory(NursingNote note);

    /**
     * @return true when a row of this effective category may be read from
     *         another hospital under the treatment presumption. Only an
     *         untagged row may.
     */
    boolean travelsCrossHospital(SensitivityCategory effectiveCategory);
}
