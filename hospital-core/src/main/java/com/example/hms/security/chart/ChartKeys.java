package com.example.hms.security.chart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * QA D3863: the request values that name a patient's chart, directly or
 * through a record of theirs. {@link RestrictedChartGuardInterceptor} reads
 * them off a GET before the handler runs, and
 * {@code RestrictedChartEnforcementGuardTest} fails when a GET names a patient
 * or a record under a name this table does not know.
 */
public final class ChartKeys {

    /** What a key names; {@link RestrictedChartGate#ownerOf} turns it into the patient. */
    public enum Owner { PATIENT, ENCOUNTER, ADMISSION, PRESCRIPTION, NURSING_NOTE, REFERRAL, IMAGING_REPORT, IMAGING_IMAGE }

    /** A path variable or request parameter, by its name, wherever it appears. */
    public static final Map<String, Owner> BY_NAME = Map.of(
        "patientId", Owner.PATIENT,
        "encounterId", Owner.ENCOUNTER,
        "admissionId", Owner.ADMISSION,
        "prescriptionId", Owner.PRESCRIPTION);

    /** {@code {id}} directly under one of these route prefixes. */
    public static final Map<String, Owner> ID_UNDER = Map.of(
        "/patients/", Owner.PATIENT,
        "/encounters/", Owner.ENCOUNTER,
        "/admissions/", Owner.ADMISSION,
        "/prescriptions/", Owner.PRESCRIPTION);

    /** A path variable whose name is only meaningful under its route prefix: prefix → (variable → owner). */
    public static final Map<String, Map<String, Owner>> SCOPED;

    static {
        Map<String, Map<String, Owner>> scoped = new LinkedHashMap<>();
        scoped.put("/imaging/dicom/reports/", Map.of("reportId", Owner.IMAGING_REPORT));
        scoped.put("/imaging/images/", Map.of("imageId", Owner.IMAGING_IMAGE));
        scoped.put("/nurse/notes/", Map.of("noteId", Owner.NURSING_NOTE));
        scoped.put("/referrals/", Map.of("referralId", Owner.REFERRAL));
        SCOPED = Map.copyOf(scoped);
    }

    /** Request parameters read as keys: the patient and the visit. */
    public static final Set<String> PARAMETERS = Set.of("patientId", "encounterId");

    private ChartKeys() {
    }

    /** What the path variable {@code name} names on a route matched by {@code pattern}, if anything. */
    public static Optional<Owner> ofPathVariable(String pattern, String name) {
        if (name == null) {
            return Optional.empty();
        }
        Owner byName = BY_NAME.get(name);
        if (byName != null) {
            return Optional.of(byName);
        }
        if (pattern == null) {
            return Optional.empty();
        }
        if ("id".equals(name)) {
            for (Map.Entry<String, Owner> under : ID_UNDER.entrySet()) {
                if (pattern.startsWith(under.getKey() + "{id}")) {
                    return Optional.of(under.getValue());
                }
            }
            return Optional.empty();
        }
        for (Map.Entry<String, Map<String, Owner>> scoped : SCOPED.entrySet()) {
            if (pattern.startsWith(scoped.getKey()) && scoped.getValue().containsKey(name)) {
                return Optional.of(scoped.getValue().get(name));
            }
        }
        return Optional.empty();
    }
}
