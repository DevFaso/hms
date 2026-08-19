package com.example.hms.fhir;

import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the P1 #1 terminology binding rollout for LabResult → Observation:
 * LOINC coding must be emitted as the primary coding when the lab definition
 * carries one, with the legacy {@code urn:hms:lab:test-code} retained as a
 * secondary identifier for internal callers.
 */
class ObservationFhirMapperLabResultTest {

    private final ObservationFhirMapper mapper = new ObservationFhirMapper();

    @Test
    void emitsLoincCodingAsPrimaryWhenDefinitionHasLoinc() {
        Observation out = mapper.toFhir(buildResult(definition("HGB", "Hemoglobin", "718-7", "Hemoglobin [Mass/volume] in Blood")));

        assertThat(out).isNotNull();
        assertThat(out.getCode().getText()).isEqualTo("Hemoglobin");
        assertThat(out.getCode().getCoding()).hasSize(2);

        Coding primary = out.getCode().getCoding().get(0);
        assertThat(primary.getSystem()).isEqualTo("http://loinc.org");
        assertThat(primary.getCode()).isEqualTo("718-7");
        assertThat(primary.getDisplay()).isEqualTo("Hemoglobin [Mass/volume] in Blood");

        Coding secondary = out.getCode().getCoding().get(1);
        assertThat(secondary.getSystem()).isEqualTo("urn:hms:lab:test-code");
        assertThat(secondary.getCode()).isEqualTo("HGB");
    }

    @Test
    void fallsBackToLocalSystemWhenLoincAbsent() {
        Observation out = mapper.toFhir(buildResult(definition("HGB", "Hemoglobin", null, null)));

        assertThat(out.getCode().getCoding()).hasSize(1);
        Coding only = out.getCode().getCodingFirstRep();
        assertThat(only.getSystem()).isEqualTo("urn:hms:lab:test-code");
        assertThat(only.getCode()).isEqualTo("HGB");
    }

    @Test
    void usesLabNameAsLoincDisplayFallbackWhenLoincDisplayBlank() {
        Observation out = mapper.toFhir(buildResult(definition("WBC", "White Blood Cells", "6690-2", null)));

        Coding primary = out.getCode().getCodingFirstRep();
        assertThat(primary.getSystem()).isEqualTo("http://loinc.org");
        assertThat(primary.getCode()).isEqualTo("6690-2");
        assertThat(primary.getDisplay()).isEqualTo("White Blood Cells");
    }

    /* ---------- helpers ---------- */

    private static LabTestDefinition definition(String testCode, String name, String loincCode, String loincDisplay) {
        return LabTestDefinition.builder()
            .testCode(testCode)
            .name(name)
            .loincCode(loincCode)
            .loincDisplay(loincDisplay)
            .build();
    }

    private static LabResult buildResult(LabTestDefinition definition) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(definition);

        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue("12.5");
        result.setResultUnit("g/dL");
        result.setResultDate(LocalDateTime.now());
        result.setReleased(true);
        return result;
    }
}
