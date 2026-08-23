package com.example.hms.service.support;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientChartAccessTest {

    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final PatientHospitalRegistrationRepository registrationRepository =
        mock(PatientHospitalRegistrationRepository.class);

    private PatientChartAccess access;

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID CALLER_HOSPITAL = UUID.randomUUID();

    private Patient patient;

    @BeforeEach
    void setUp() {
        access = new PatientChartAccess(patientRepository, registrationRepository);
        patient = Patient.builder().firstName("Aminata").lastName("Sawadogo").build();
        patient.setId(PATIENT_ID);
    }

    @Test
    @DisplayName("resolves a cross-hospital patient the tenant-scoped finder would miss")
    void resolvesCrossHospitalPatient() {
        // The regression this class exists for: Patient.hospitalId holds the
        // patient's FIRST hospital, so findById (tenant-scoped) returns empty
        // for a caller at the second one. findByIdUnscoped must be used instead.
        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(PATIENT_ID, CALLER_HOSPITAL))
            .thenReturn(true);

        assertThat(access.require(PATIENT_ID, CALLER_HOSPITAL)).isSameAs(patient);
        verify(patientRepository, never()).findById(any(UUID.class));
    }

    @Test
    @DisplayName("404s when the patient has no registration at the caller's hospital")
    void deniesUnregisteredPatient() {
        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(PATIENT_ID, CALLER_HOSPITAL))
            .thenReturn(false);

        assertThatThrownBy(() -> access.require(PATIENT_ID, CALLER_HOSPITAL))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("404s identically for an unknown patient — 404-not-403")
    void unknownAndUnregisteredAreIndistinguishable() {
        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException unknown = catchNotFound(PATIENT_ID, CALLER_HOSPITAL);

        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.of(patient));
        when(registrationRepository.existsByPatientIdAndHospitalId(PATIENT_ID, CALLER_HOSPITAL))
            .thenReturn(false);
        ResourceNotFoundException unregistered = catchNotFound(PATIENT_ID, CALLER_HOSPITAL);

        // Same key AND same args: a caller must not be able to tell "no such
        // patient" from "not yours" by comparing responses.
        assertThat(unregistered.getMessageKey()).isEqualTo(unknown.getMessageKey());
        assertThat(unregistered.getArgs()).isEqualTo(unknown.getArgs());
    }

    @Test
    @DisplayName("throws a resolvable message KEY, never a prose sentence")
    void throwsAMessageKeyNotProse() {
        // Regression: services passed "Patient not found with ID: " + id as the
        // key, so MessageUtil could never resolve it and the UI rendered
        // "[Missing translation] Patient not found with ID: ...".
        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException thrown = catchNotFound(PATIENT_ID, CALLER_HOSPITAL);

        assertThat(thrown.getMessageKey()).isEqualTo("patient.notFound");
        assertThat(thrown.getMessageKey()).doesNotContain(" ");
        assertThat(thrown.getArgs()).containsExactly(PATIENT_ID);
    }

    @Test
    @DisplayName("skips the registration check when unscoped (super admin)")
    void allowsUnscopedCaller() {
        when(patientRepository.findByIdUnscoped(PATIENT_ID)).thenReturn(Optional.of(patient));

        assertThat(access.require(PATIENT_ID, null)).isSameAs(patient);
        verify(registrationRepository, never())
            .existsByPatientIdAndHospitalId(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("a null patient id is a 404, not a NullPointerException")
    void nullPatientIdIsNotFound() {
        assertThatThrownBy(() -> access.require(null, CALLER_HOSPITAL))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientRepository, never()).findByIdUnscoped(any());
    }

    private ResourceNotFoundException catchNotFound(UUID patientId, UUID hospitalId) {
        try {
            access.require(patientId, hospitalId);
        } catch (ResourceNotFoundException e) {
            return e;
        }
        throw new AssertionError("expected ResourceNotFoundException");
    }
}
