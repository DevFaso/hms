package com.example.hms.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * QA D912: a reopened prescription for a phone-only patient showed the name
 * alone, because the response carried the email and nothing else to fall
 * back on.
 */
class PrescriptionMapperPatientContactTest {

    private final PrescriptionMapper mapper = new PrescriptionMapper();

    @Test
    void carriesThePatientsPhoneBesideTheEmail() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Fatimata");
        patient.setLastName("Zongo");
        patient.setPhoneNumberPrimary("70001003");
        Prescription rx = new Prescription();
        rx.setPatient(patient);

        PrescriptionResponseDTO dto = mapper.toResponseDTO(rx);

        assertThat(dto.getPatientEmail()).isEmpty();
        assertThat(dto.getPatientPhone()).isEqualTo("70001003");
    }

    @Test
    void sendsAnEmptyPhoneWithoutAPatient() {
        assertThat(mapper.toResponseDTO(new Prescription()).getPatientPhone()).isEmpty();
    }
}
