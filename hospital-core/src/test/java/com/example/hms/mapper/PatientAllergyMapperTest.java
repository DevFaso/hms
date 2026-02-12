package com.example.hms.mapper;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.payload.dto.PatientAllergyRequestDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PatientAllergyMapperTest {

    private final PatientAllergyMapper mapper = new PatientAllergyMapper();

    @Test
    void updateEntityFromRequestPopulatesAndTrimsFields() {
        UUID patientId = UUID.randomUUID();
        PatientAllergyRequestDTO request = PatientAllergyRequestDTO.builder()
            .allergenDisplay("  Penicillin  ")
            .allergenCode("  RX123  ")
            .category("  DRUG  ")
            .severity(AllergySeverity.SEVERE)
            .verificationStatus(AllergyVerificationStatus.CONFIRMED)
            .reaction("  Rash  ")
            .reactionNotes("  Patient reports rash after exposure.  ")
            .onsetDate(LocalDate.of(2020, 1, 15))
            .lastOccurrenceDate(LocalDate.of(2024, 5, 1))
            .recordedDate(LocalDate.of(2024, 5, 2))
            .sourceSystem("  EHR  ")
            .active(Boolean.FALSE)
            .build();

        PatientAllergy allergy = new PatientAllergy();
        allergy.setPatient(new Patient());
        allergy.getPatient().setId(patientId);
        allergy.setActive(true);

        mapper.updateEntityFromRequest(request, allergy);

        assertThat(allergy.getAllergenDisplay()).isEqualTo("Penicillin");
        assertThat(allergy.getAllergenCode()).isEqualTo("RX123");
        assertThat(allergy.getCategory()).isEqualTo("DRUG");
        assertThat(allergy.getSeverity()).isEqualTo(AllergySeverity.SEVERE);
        assertThat(allergy.getVerificationStatus()).isEqualTo(AllergyVerificationStatus.CONFIRMED);
        assertThat(allergy.getReaction()).isEqualTo("Rash");
        assertThat(allergy.getReactionNotes()).isEqualTo("Patient reports rash after exposure.");
        assertThat(allergy.getOnsetDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(allergy.getLastOccurrenceDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(allergy.getRecordedDate()).isEqualTo(LocalDate.of(2024, 5, 2));
        assertThat(allergy.getSourceSystem()).isEqualTo("EHR");
        assertThat(allergy.isActive()).isFalse();
    }

    @Test
    void updateEntityFromRequestIgnoresNullInputs() {
        PatientAllergy allergy = new PatientAllergy();
        allergy.setAllergenDisplay("Existing");
        allergy.setActive(true);

        assertThatNoException().isThrownBy(() -> mapper.updateEntityFromRequest(null, allergy));
        assertThatNoException().isThrownBy(() -> mapper.updateEntityFromRequest(new PatientAllergyRequestDTO(), null));
        assertThat(allergy.getAllergenDisplay()).isEqualTo("Existing");
        assertThat(allergy.isActive()).isTrue();
    }
}
