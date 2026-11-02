package com.example.hms.mapper.pharmacy;

import com.example.hms.model.Patient;
import com.example.hms.model.pharmacy.PharmacyClaim;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PharmacyClaimMapperTest {

    private final PharmacyClaimMapper mapper = new PharmacyClaimMapper();

    /** QA D992 class: the claims list showed "3f2a9c1b…" where the patient's name belongs. */
    @Test
    void d992_theResponseNamesThePatient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Test");
        PharmacyClaim claim = PharmacyClaim.builder().patient(patient).build();

        assertThat(mapper.toResponseDTO(claim).getPatientName()).isEqualTo("Awa Test");
        assertThat(mapper.toResponseDTO(claim).getPatientId()).isEqualTo(patient.getId());
    }

    @Test
    void d992_aClaimWithNoPatientHasNoName() {
        assertThat(mapper.toResponseDTO(PharmacyClaim.builder().build()).getPatientName()).isNull();
    }
}
