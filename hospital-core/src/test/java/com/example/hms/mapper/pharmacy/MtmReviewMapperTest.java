package com.example.hms.mapper.pharmacy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.pharmacy.MtmReview;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D900: the MTM list showed the patient as a raw UUID. The review answers
 * with the patient's name and the MRN at the review's own hospital.
 */
class MtmReviewMapperTest {

    private final MtmReviewMapper mapper = new MtmReviewMapper();

    private static Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        return h;
    }

    private static PatientHospitalRegistration registration(Patient patient, Hospital hospital, String mrn) {
        PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn(mrn).active(true).build();
        reg.setId(UUID.randomUUID());
        return reg;
    }

    @Test
    void theReviewNamesItsPatientAndTheMrnAtItsOwnHospital() {
        Hospital here = hospital();
        Hospital elsewhere = hospital();
        Patient patient = Patient.builder().firstName("Seydou").lastName("Traoré").build();
        patient.setId(UUID.randomUUID());
        patient.setHospitalRegistrations(new HashSet<>(List.of(
            registration(patient, elsewhere, "OTH0007"),
            registration(patient, here, "CHU0421"))));
        MtmReview review = MtmReview.builder().patient(patient).hospital(here).build();
        review.setId(UUID.randomUUID());

        MtmReviewResponseDTO dto = mapper.toResponseDTO(review);

        assertThat(dto.getPatientId()).isEqualTo(patient.getId());
        assertThat(dto.getPatientName()).isEqualTo("Seydou Traoré");
        assertThat(dto.getPatientMrn()).isEqualTo("CHU0421");
    }

    @Test
    void aPatientWithNoRegistrationHereHasNoMrnRatherThanAnotherHospitals() {
        Hospital here = hospital();
        Patient patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(UUID.randomUUID());
        patient.setHospitalRegistrations(new HashSet<>(List.of(registration(patient, hospital(), "OTH0001"))));
        MtmReview review = MtmReview.builder().patient(patient).hospital(here).build();

        MtmReviewResponseDTO dto = mapper.toResponseDTO(review);

        assertThat(dto.getPatientName()).isEqualTo("Awa Kaboré");
        assertThat(dto.getPatientMrn()).isNull();
    }
}
