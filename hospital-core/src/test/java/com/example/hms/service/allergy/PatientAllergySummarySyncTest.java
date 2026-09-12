package com.example.hms.service.allergy;

import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientAllergySummarySyncTest {

    @Mock
    private PatientAllergyRepository allergyRepository;

    @Mock
    private PatientRepository patientRepository;

    @InjectMocks
    private PatientAllergySummarySync sync;

    @Test
    @DisplayName("the summary lists active allergens, most severe first, without duplicates")
    void summarizesActiveRowsMostSevereFirst() {
        List<PatientAllergy> rows = List.of(
            row("garlic", AllergySeverity.MILD, true),
            row("Peanuts", AllergySeverity.LIFE_THREATENING, true),
            row("Latex", AllergySeverity.SEVERE, false),
            row("peanuts", null, true));

        assertThat(PatientAllergySummarySync.summarize(rows)).isEqualTo("Peanuts, garlic");
        assertThat(PatientAllergySummarySync.summarize(List.of())).isNull();
        assertThat(PatientAllergySummarySync.summarize(List.of(row("Latex", AllergySeverity.SEVERE, false)))).isNull();
    }

    @Test
    @DisplayName("refresh rewrites the patient column only when it changed")
    void refreshWritesOnlyOnChange() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setAllergies("Peanuts");
        when(allergyRepository.findByPatient_Id(patient.getId()))
            .thenReturn(List.of(row("Peanuts", AllergySeverity.SEVERE, true)));

        assertThat(sync.refresh(patient)).isEqualTo("Peanuts");
        verify(patientRepository, never()).save(patient);

        when(allergyRepository.findByPatient_Id(patient.getId()))
            .thenReturn(List.of(row("Peanuts", AllergySeverity.SEVERE, true), row("Garlic", null, true)));

        assertThat(sync.refresh(patient)).isEqualTo("Peanuts, Garlic");
        assertThat(patient.getAllergies()).isEqualTo("Peanuts, Garlic");
        verify(patientRepository).save(patient);
    }

    @Test
    @DisplayName("the summary never exceeds the column width")
    void summaryStaysWithinTheColumn() {
        List<PatientAllergy> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("Allergen number " + i + " " + "x".repeat(40), null, true));
        }
        String summary = PatientAllergySummarySync.summarize(rows);
        assertThat(summary).isNotNull();
        assertThat(summary.length()).isLessThanOrEqualTo(PatientAllergySummarySync.MAX_SUMMARY);
        assertThat(summary).doesNotEndWith(",");
    }

    private static PatientAllergy row(String display, AllergySeverity severity, boolean active) {
        return PatientAllergy.builder().allergenDisplay(display).severity(severity).active(active).build();
    }
}
