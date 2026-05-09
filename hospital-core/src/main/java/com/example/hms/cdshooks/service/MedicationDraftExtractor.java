package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.terminology.RxNormCodingExtractor;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a draft {@code MedicationRequest} payload (CDS Hooks 1.0 wire shape:
 * a loosely-typed {@link Map}) and pulls the three fields the rule engine
 * needs: human-readable name, terminology code, and dose.
 *
 * <p>The extractor prefers an RxNorm-system coding when one is present
 * (via {@link RxNormCodingExtractor}); otherwise it falls back to the
 * first {@code coding[]} entry's {@code code} — matching the legacy
 * behaviour of {@code OrderSignRulesCdsService}'s private extractor. This
 * RxNorm-first preference is what makes {@code order-select},
 * {@code medication-prescribe}, and the extended
 * {@code hms-medication-allergy-check} (drug-drug interaction add-on,
 * v1.0 row 3) resolve cleanly when a FHIR client only carries the
 * canonical RxCUI.
 *
 * <p>Stateless utility — pure JVM, no Spring, no persistence.
 */
public final class MedicationDraftExtractor {

    private MedicationDraftExtractor() { /* static-only */ }

    /** A medication draft reduced to the three fields the rule engine consumes. */
    public record ProposedMedication(String name, String code, String dose) { }

    /**
     * Extract from a draft {@code MedicationRequest} resource map. Returns a
     * {@link ProposedMedication} whose fields may individually be null when
     * the corresponding payload key is missing or shaped unexpectedly. The
     * extractor never throws on malformed input.
     */
    public static ProposedMedication extract(Map<String, Object> draft) {
        if (draft == null) return new ProposedMedication(null, null, null);
        Object cc = draft.get("medicationCodeableConcept");
        String name = textFromCodeableConcept(cc);
        if (name == null) name = textFromReference(draft.get("medicationReference"));
        String code = RxNormCodingExtractor.rxnormFromCodeableConcept(cc)
            .orElseGet(() -> codeFromCodeableConcept(cc));
        String dose = doseFromDosageInstruction(draft.get("dosageInstruction"));
        return new ProposedMedication(name, code, dose);
    }

    private static String textFromCodeableConcept(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        String text = nonBlank(map.get("text"));
        if (text != null) return text;
        return textFromFirstCoding(map.get("coding"));
    }

    private static String codeFromCodeableConcept(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Object coding = map.get("coding");
        if (!(coding instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> first)) return null;
        return nonBlank(first.get("code"));
    }

    private static String textFromFirstCoding(Object coding) {
        if (!(coding instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> first)) return null;
        String display = nonBlank(first.get("display"));
        return display != null ? display : nonBlank(first.get("code"));
    }

    private static String textFromReference(Object value) {
        if (!(value instanceof Map<?, ?> ref)) return null;
        return nonBlank(ref.get("display"));
    }

    private static String doseFromDosageInstruction(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> instr)) return null;
        String text = nonBlank(instr.get("text"));
        if (text != null) return text;
        return doseFromQuantity(instr.get("doseQuantity"));
    }

    private static String doseFromQuantity(Object value) {
        if (!(value instanceof Map<?, ?> q)) return null;
        Object v = q.get("value");
        Object u = q.get("unit");
        if (v == null) return null;
        return u == null
            ? v.toString()
            : v + " " + u.toString().toLowerCase(Locale.ROOT);
    }

    private static String nonBlank(Object value) {
        return (value instanceof String s && !s.isBlank()) ? s : null;
    }
}
