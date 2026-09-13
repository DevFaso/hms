package com.example.hms.service.allergy;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyAllergyTextBackfillWorkerTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientAllergyRepository allergyRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private LegacyAllergyTextImporter importer;

    @InjectMocks
    private LegacyAllergyTextBackfillWorker worker;

    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("free text at the patient's first hospital is imported as LEGACY rows")
    void importsAtTheFirstHospital() {
        patient.setAllergies("Peanuts, garlic");
        patient.setHospitalId(hospital.getId());
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(allergyRepository.existsByPatient_IdAndSourceSystem(eq(patient.getId()), anyString())).thenReturn(false);
        when(hospitalRepository.findById(hospital.getId())).thenReturn(Optional.of(hospital));

        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED);
        verify(importer).importFreeText(patient, hospital, null, "Peanuts, garlic", LegacyAllergyTextImporter.SOURCE_LEGACY);
    }

    @Test
    @DisplayName("a patient without a first hospital uses their first registration")
    void fallsBackToTheFirstRegistration() {
        patient.setAllergies("Latex");
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(allergyRepository.existsByPatient_IdAndSourceSystem(eq(patient.getId()), anyString())).thenReturn(false);
        when(registrationRepository.findByPatientId(patient.getId())).thenReturn(List.of(registration));

        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED);
        verify(importer).importFreeText(patient, hospital, null, "Latex", LegacyAllergyTextImporter.SOURCE_LEGACY);
    }

    @Test
    @DisplayName("a patient already carrying free-text import rows is skipped — the backfill is idempotent")
    void skipsWhenAlreadyImported() {
        patient.setAllergies("Peanuts");
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(allergyRepository.existsByPatient_IdAndSourceSystem(patient.getId(), LegacyAllergyTextImporter.SOURCE_LEGACY)).thenReturn(true);

        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.ALREADY_DONE);
        verify(importer, never()).importFreeText(any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a statement of no allergies is left as the clerk wrote it")
    void leavesNoneStatementsAlone() {
        patient.setAllergies("Aucune allergie connue");
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(allergyRepository.existsByPatient_IdAndSourceSystem(eq(patient.getId()), anyString())).thenReturn(false);

        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_TO_IMPORT);
        verify(importer, never()).importFreeText(any(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("no hospital anywhere means no row can be written; the text stays")
    void reportsMissingHospital() {
        patient.setAllergies("Peanuts");
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(allergyRepository.existsByPatient_IdAndSourceSystem(eq(patient.getId()), anyString())).thenReturn(false);
        when(registrationRepository.findByPatientId(patient.getId())).thenReturn(List.of());

        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.NO_HOSPITAL);
    }

    @Test
    @DisplayName("blank text and an unknown patient are reported, not thrown")
    void blankAndMissing() {
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        assertThat(worker.backfillOne(patient.getId())).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_TO_IMPORT);

        UUID unknown = UUID.randomUUID();
        when(patientRepository.findByIdUnscoped(unknown)).thenReturn(Optional.empty());
        assertThat(worker.backfillOne(unknown)).isEqualTo(LegacyAllergyTextBackfillWorker.Outcome.MISSING);
    }
}
