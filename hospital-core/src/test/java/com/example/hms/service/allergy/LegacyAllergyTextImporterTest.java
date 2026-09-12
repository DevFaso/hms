package com.example.hms.service.allergy;

import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyAllergyTextImporterTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private PatientAllergyRepository allergyRepository;

    @Mock
    private PatientAllergySummarySync summarySync;

    private LegacyAllergyTextImporter importer;

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        importer = new LegacyAllergyTextImporter(allergyRepository, summarySync, FIXED);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("each allergen becomes an UNCONFIRMED row at the hospital, keeping the original text, then the summary is refreshed")
    void createsUnconfirmedRowsAndRefreshes() {
        when(allergyRepository.findByPatient_Id(patient.getId())).thenReturn(List.of());

        int created = importer.importFreeText(patient, hospital, null, "Pénicilline, arachide (urticaire)", LegacyAllergyTextImporter.SOURCE_REGISTRATION);

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<PatientAllergy> captor = ArgumentCaptor.forClass(PatientAllergy.class);
        verify(allergyRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(PatientAllergy::getAllergenDisplay)
            .containsExactly("Pénicilline", "arachide (urticaire)");
        assertThat(captor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getPatient()).isSameAs(patient);
            assertThat(row.getHospital()).isSameAs(hospital);
            assertThat(row.getVerificationStatus()).isEqualTo(AllergyVerificationStatus.UNCONFIRMED);
            assertThat(row.getSourceSystem()).isEqualTo(LegacyAllergyTextImporter.SOURCE_REGISTRATION);
            assertThat(row.getRecordedDate()).isEqualTo(LocalDate.of(2026, 9, 11));
            assertThat(row.getReactionNotes()).contains("Pénicilline, arachide (urticaire)");
            assertThat(row.isActive()).isTrue();
        });
        verify(summarySync).refresh(patient);
    }

    @Test
    @DisplayName("an allergen already recorded as an active row is not duplicated")
    void skipsAllergensAlreadyRecorded() {
        PatientAllergy existing = PatientAllergy.builder().allergenDisplay("PENICILLINE").active(true).build();
        PatientAllergy inactive = PatientAllergy.builder().allergenDisplay("Latex").active(false).build();
        when(allergyRepository.findByPatient_Id(patient.getId())).thenReturn(List.of(existing, inactive));

        int created = importer.importFreeText(patient, hospital, null, "Penicilline; latex", LegacyAllergyTextImporter.SOURCE_LEGACY);

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<PatientAllergy> captor = ArgumentCaptor.forClass(PatientAllergy.class);
        verify(allergyRepository).save(captor.capture());
        assertThat(captor.getValue().getAllergenDisplay()).isEqualTo("latex");
        verify(summarySync).refresh(patient);
    }

    @Test
    @DisplayName("text that names no allergen writes nothing and does not touch the summary")
    void noneTextIsANoOp() {
        assertThat(importer.importFreeText(patient, hospital, null, "Aucune allergie connue", LegacyAllergyTextImporter.SOURCE_LEGACY)).isZero();
        assertThat(importer.importFreeText(patient, hospital, null, "  ", LegacyAllergyTextImporter.SOURCE_LEGACY)).isZero();
        verify(allergyRepository, never()).save(any());
        verify(summarySync, never()).refresh(any());
    }

    @Test
    @DisplayName("the notes never exceed their column even for a very long original text")
    void notesAreBounded() {
        when(allergyRepository.findByPatient_Id(patient.getId())).thenReturn(List.of());
        String text = "Peanuts, " + "y".repeat(3000);

        importer.importFreeText(patient, hospital, null, text, LegacyAllergyTextImporter.SOURCE_LEGACY);

        ArgumentCaptor<PatientAllergy> captor = ArgumentCaptor.forClass(PatientAllergy.class);
        verify(allergyRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row ->
            assertThat(row.getReactionNotes().length()).isLessThanOrEqualTo(LegacyAllergyTextImporter.MAX_NOTES));
    }
}
