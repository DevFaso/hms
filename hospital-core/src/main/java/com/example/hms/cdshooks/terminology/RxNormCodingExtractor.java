package com.example.hms.cdshooks.terminology;

import com.example.hms.terminology.TerminologyCodes;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts an RxNorm RxCUI from the loosely-typed CDS Hooks 1.0 JSON shapes
 * that {@code order-select} and {@code medication-prescribe} payloads use
 * to identify medications.
 *
 * <p>The CDS Hooks contract delivers FHIR resources as bare maps (no
 * HAPI-FHIR types). A {@code MedicationRequest} commonly carries a
 * {@code medicationCodeableConcept} with multiple {@code coding[]} entries —
 * one per terminology system. This extractor walks that array and returns
 * the first entry whose {@code system} is the canonical RxNorm system URI
 * ({@link TerminologyCodes#SYSTEM_RXNORM}) <em>and</em> whose {@code code}
 * passes {@link TerminologyCodes#isValidRxNorm(String)} — so a malformed
 * RxCUI cannot leak into the engine's catalog lookup.
 *
 * <p>Stateless — kept package-private static so the call sites (and the
 * unit test) remain trivial. No dependency on Spring or persistence.
 */
public final class RxNormCodingExtractor {

    private RxNormCodingExtractor() { /* static-only */ }

    /**
     * Pulls the first valid RxCUI out of a {@code medicationCodeableConcept}
     * map. Returns {@link Optional#empty()} when the input is null, the
     * shape is unexpected, no coding entry advertises the RxNorm system,
     * or the matching code is malformed.
     */
    public static Optional<String> rxnormFromCodeableConcept(Object codeableConcept) {
        if (!(codeableConcept instanceof Map<?, ?> cc)) return Optional.empty();
        Object coding = cc.get("coding");
        if (!(coding instanceof List<?> codings) || codings.isEmpty()) return Optional.empty();
        for (Object entry : codings) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Object system = map.get("system");
            if (system == null || !TerminologyCodes.SYSTEM_RXNORM.equals(system.toString())) {
                continue;
            }
            Object code = map.get("code");
            if (code == null) continue;
            String trimmed = code.toString().trim();
            if (TerminologyCodes.isValidRxNorm(trimmed)) {
                return Optional.of(trimmed);
            }
        }
        return Optional.empty();
    }

    /**
     * Convenience for reading an RxCUI directly off a draft {@code Medication-
     * Request} resource map. Looks first at {@code medicationCodeableConcept},
     * which is the FHIR-normative location for medication identifiers.
     */
    public static Optional<String> rxnormFromMedicationRequest(Map<String, Object> medicationRequest) {
        if (medicationRequest == null) return Optional.empty();
        return rxnormFromCodeableConcept(medicationRequest.get("medicationCodeableConcept"));
    }
}
