package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabResultResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D527: the staff lab-results payload carried the patient's e-mail although
 * no screen reads it off a result (the portal stopped rendering it, and the
 * patient apps take their own contact from the account). It is no longer sent.
 */
class LabResultMapperPatientEmailTest {

    @Test
    void aResultNeverCarriesThePatientsEmail() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        patient.setEmail("awa@example.test");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(new LabTestDefinition());
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue("5.4");

        LabResultResponseDTO dto = new LabResultMapper().toResponseDTO(result);

        assertThat(dto.getPatientFullName()).isEqualTo("Awa Traore");
        assertThat(dto.getPatientEmail()).isNull();
    }
}
