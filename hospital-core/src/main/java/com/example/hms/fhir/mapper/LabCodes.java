package com.example.hms.fhir.mapper;

import com.example.hms.model.LabTestDefinition;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;

/**
 * The one place a lab test definition becomes a FHIR code, shared by the
 * ServiceRequest and DiagnosticReport mappers so the coding order cannot
 * drift between them: LOINC primary when bound (the P1 #1 rollout), the
 * local {@code urn:hms:lab:test-code} kept as a secondary identifier for
 * internal callers that index by the formulary code.
 */
final class LabCodes {

    private static final String LOINC = "http://loinc.org";

    private LabCodes() {}

    static CodeableConcept codeFor(LabTestDefinition def, String fallbackText) {
        CodeableConcept code = new CodeableConcept();
        if (def == null) {
            code.setText(fallbackText);
            return code;
        }
        if (def.getName() != null) code.setText(def.getName());
        if (def.getLoincCode() != null && !def.getLoincCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem(LOINC)
                .setCode(def.getLoincCode())
                .setDisplay(def.getLoincDisplay() != null ? def.getLoincDisplay() : def.getName()));
        }
        if (def.getTestCode() != null && !def.getTestCode().isBlank()) {
            code.addCoding(new Coding()
                .setSystem("urn:hms:lab:test-code")
                .setCode(def.getTestCode())
                .setDisplay(def.getName()));
        }
        return code;
    }
}
