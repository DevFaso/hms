package com.example.hms.cdshooks.terminology;

import com.example.hms.terminology.TerminologyCodes;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Project-local LOINC binding table for the hms-patient-view CDS Hooks
 * service (roadmap row 26).
 *
 * <p>HMS stores problem-list entries in ICD-10 (the {@code problem_code}
 * column on {@code clinical.patient_problems}). For Cerner / Epic /
 * SMART-on-FHIR consumers that expect a LOINC alongside the ICD coding
 * — typically as a way to surface the most clinically-relevant
 * observation panel for a given problem — we expose the binding here.
 *
 * <p>The table is intentionally small and curated: it covers the chronic
 * conditions that drive the West-African pilot deployments (hypertension,
 * type 2 diabetes, sickle cell disease, asthma, COPD, malaria,
 * HIV/AIDS, tuberculosis) and the obstetric / pediatric flags that the
 * BPA layer already keys off. Codes not in the table return
 * {@link Optional#empty()} from {@link #bindingFor(String)}; the calling
 * CDS service ({@link com.example.hms.cdshooks.service.PatientViewCdsService})
 * then omits the {@code [LOINC: …]} annotation from the rendered card
 * detail line and continues to surface the ICD coding alone. The card
 * itself is never dropped — only the optional LOINC annotation.
 *
 * <p>The LOINC codes chosen here are <em>observation-panel</em> codes,
 * not diagnosis codes (LOINC doesn't model diagnoses). They are what
 * a CDS-consumer "what should I order for this problem?" workflow
 * would suggest. Anything stored on the entity itself as an explicit
 * {@code loincCode} overrides this default — the entity wins.
 *
 * <p>No external HTTP / FHIR terminology server is consulted at runtime
 * — same rationale as the RxNorm seed list in V93: intermittent
 * connectivity in the deployment environment makes a local table the
 * only honest answer.
 */
public final class ProblemLoincBindings {

    // ── Panels shared by more than one ICD prefix ──────────────────
    // Named once so the table below reads as "these problems point at the
    // SAME panel", which is the clinical intent, rather than as repetition.
    // The record is immutable, so one instance is safely shared.
    private static final LoincCoding BLOOD_PRESSURE_PANEL =
        loinc("85354-9", "Blood pressure panel");
    private static final LoincCoding HBA1C =
        loinc("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood");
    private static final LoincCoding SPIROMETRY =
        loinc("19868-9", "FEV1/FVC.predicted");
    private static final LoincCoding MALARIA_PARASITE =
        loinc("32700-7", "Plasmodium sp identified in Blood");
    private static final LoincCoding TB_SPUTUM =
        loinc("19836-6", "Tuberculosis [Presence] in Sputum");

    /**
     * ICD-10 prefix → LOINC observation-panel code. Keyed on the
     * prefix (typically 3 chars: e.g. "I10", "E11") so a more specific
     * subclassification (I10.0, I10.9) still resolves. Lookup is
     * case-insensitive and trims leading/trailing whitespace.
     */
    static final Map<String, LoincCoding> ICD10_TO_LOINC = Map.ofEntries(
        // ── Cardiovascular ─────────────────────────────────────────
        Map.entry("I10",  BLOOD_PRESSURE_PANEL),                           // Essential hypertension
        Map.entry("I11",  BLOOD_PRESSURE_PANEL),                           // Hypertensive heart disease
        Map.entry("I50",  loinc("71425-3", "Heart failure assessment panel")),
        // ── Endocrine ──────────────────────────────────────────────
        Map.entry("E10",  HBA1C),
        Map.entry("E11",  HBA1C),
        Map.entry("E13",  HBA1C),
        // ── Respiratory ────────────────────────────────────────────
        Map.entry("J44",  SPIROMETRY),                                     // COPD
        Map.entry("J45",  SPIROMETRY),                                     // Asthma
        // ── Hematology (West Africa relevant: sickle cell + anemia) ─
        Map.entry("D57",  loinc("4624-3",  "Hemoglobin S [Mass/volume] in Blood")),   // Sickle cell disorders
        Map.entry("D50",  loinc("718-7",   "Hemoglobin [Mass/volume] in Blood")),     // Iron deficiency anemia
        // ── Infectious (West Africa relevant) ──────────────────────
        Map.entry("B50",  MALARIA_PARASITE),                               // P. falciparum malaria
        Map.entry("B51",  MALARIA_PARASITE),                               // P. vivax malaria
        Map.entry("B52",  MALARIA_PARASITE),                               // P. malariae
        Map.entry("B53",  MALARIA_PARASITE),                               // Other parasitologically confirmed
        Map.entry("B54",  MALARIA_PARASITE),                               // Unspecified malaria
        Map.entry("B20",  loinc("25836-8", "HIV 1 antigen and Ab panel")),            // HIV disease
        Map.entry("A15",  TB_SPUTUM),                                      // Respiratory TB, confirmed
        Map.entry("A16",  TB_SPUTUM),                                      // Respiratory TB, unconfirmed
        // ── Renal ──────────────────────────────────────────────────
        Map.entry("N18",  loinc("33914-3", "Glomerular filtration rate/1.73 sq M.predicted")),
        // ── Obstetric (relevant for the OB BPA rule) ───────────────
        Map.entry("O14",  BLOOD_PRESSURE_PANEL),                           // Pre-eclampsia
        Map.entry("O24",  HBA1C)                                           // Gestational diabetes
    );

    private ProblemLoincBindings() { /* static-only */ }

    /**
     * Resolves the LOINC binding for a given ICD-10 problem code. Returns
     * empty when:
     * <ul>
     *   <li>{@code icdCode} is null or blank,</li>
     *   <li>the code's 3-character prefix is not in the seed table.</li>
     * </ul>
     *
     * <p>Callers SHOULD prefer an explicit {@code PatientProblem.loincCode}
     * if present, and only fall back to this binding when the entity
     * itself does not carry a LOINC.
     *
     * @param icdCode raw ICD-10 value (any case, may carry a {@code .subdivision})
     * @return the LOINC coding the patient-view card should advertise
     *         alongside the ICD coding, or empty if no binding exists
     */
    public static Optional<LoincCoding> bindingFor(String icdCode) {
        if (icdCode == null) return Optional.empty();
        String trimmed = icdCode.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        if (!TerminologyCodes.isValidIcd10(trimmed)) return Optional.empty();
        String prefix = trimmed.substring(0, Math.min(3, trimmed.length()))
            .toUpperCase(Locale.ROOT);
        return Optional.ofNullable(ICD10_TO_LOINC.get(prefix));
    }

    private static LoincCoding loinc(String code, String display) {
        return new LoincCoding(code, display);
    }

    /**
     * A LOINC code + human-readable display. Mirrors the FHIR
     * {@code Coding} datatype (without the system URI, which is always
     * {@link TerminologyCodes#SYSTEM_LOINC} for this table).
     */
    public record LoincCoding(String code, String display) {}
}
