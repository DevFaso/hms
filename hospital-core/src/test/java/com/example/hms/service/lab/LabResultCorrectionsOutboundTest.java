package com.example.hms.service.lab;

import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1016: a released correction leaves the hospital marked as one - FHIR
 * {@code corrected}, HL7 v2 OBX-11 {@code C} - so a receiving system replaces
 * the value instead of filing a second final result next to the old one.
 */
class LabResultCorrectionsOutboundTest {

    private static LabResult result(boolean released, boolean correction) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        LabTestDefinition tdr = new LabTestDefinition();
        tdr.setName("TDR paludisme");
        tdr.setTestCode("TDR");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(tdr);
        LabResult r = new LabResult();
        r.setId(UUID.randomUUID());
        r.setLabOrder(order);
        r.setResultValue("Négatif");
        r.setReleased(released);
        if (correction) {
            r.setSupersedesResultId(UUID.randomUUID());
        }
        return r;
    }

    @Test
    void aCorrectionIsTheOnlyResultLabelledAsOne() {
        assertThat(LabResultCorrections.isCorrection(result(true, true))).isTrue();
        assertThat(LabResultCorrections.isCorrection(result(true, false))).isFalse();
        assertThat(LabResultCorrections.isCorrection(null)).isFalse();
    }

    @Test
    void fhirSendsAReleasedCorrectionAsCorrected() {
        ObservationFhirMapper mapper = new ObservationFhirMapper();
        assertThat(mapper.toFhir(result(true, true)).getStatus()).isEqualTo(Observation.ObservationStatus.CORRECTED);
        assertThat(mapper.toFhir(result(true, false)).getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
        assertThat(mapper.toFhir(result(false, true)).getStatus()).isEqualTo(Observation.ObservationStatus.PRELIMINARY);
    }

    @Test
    void hl7SendsAReleasedCorrectionWithObx11C() {
        Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();
        assertThat(obx11(builder.buildOruR01(result(true, true)))).isEqualTo("C");
        assertThat(obx11(builder.buildOruR01(result(true, false)))).isEqualTo("F");
        assertThat(obx11(builder.buildOruR01(result(false, false)))).isEqualTo("P");
    }

    private static String obx11(String message) {
        for (String segment : message.split("\r")) {
            if (segment.startsWith("OBX|")) {
                return segment.split("\\|", -1)[11];
            }
        }
        throw new AssertionError("no OBX segment");
    }
}
