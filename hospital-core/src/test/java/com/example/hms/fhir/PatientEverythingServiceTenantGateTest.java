package com.example.hms.fhir;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.MethodNotAllowedException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.everything.PatientEverythingService;
import com.example.hms.fhir.mapper.ConditionFhirMapper;
import com.example.hms.fhir.mapper.EncounterFhirMapper;
import com.example.hms.fhir.mapper.MedicationRequestFhirMapper;
import com.example.hms.fhir.mapper.ObservationFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests focused on the PR #352 Copilot finding (High):
 * {@code PatientRepository.findById} is not tenant-aware, so the
 * service must gate Patient rendering on
 * {@code PatientHospitalRegistrationRepository.findByPatientIdAndHospitalId}.
 *
 * <p>These tests cover the security branches in isolation
 * (flag-off / no-context / cross-tenant / unknown-patient) with
 * mocked collaborators — the wire contract is exercised by the
 * existing {@code PatientEverythingIT} / {@code PatientEverythingEnabledIT}.
 */
class PatientEverythingServiceTenantGateTest {

    private FhirOperationsProperties properties;
    private PatientRepository patientRepository;
    private PatientHospitalRegistrationRepository registrationRepository;
    private PatientFhirMapper patientMapper;
    private AuditEventLogService auditService;
    private PatientEverythingService service;

    private final UUID activeHospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new FhirOperationsProperties();
        patientRepository = mock(PatientRepository.class);
        registrationRepository = mock(PatientHospitalRegistrationRepository.class);
        patientMapper = mock(PatientFhirMapper.class);
        auditService = mock(AuditEventLogService.class);
        service = new PatientEverythingService(
            properties,
            patientRepository,
            registrationRepository,
            mock(EncounterRepository.class),
            mock(PatientVitalSignRepository.class),
            mock(LabResultRepository.class),
            mock(PatientProblemRepository.class),
            mock(PrescriptionRepository.class),
            patientMapper,
            mock(EncounterFhirMapper.class),
            mock(ObservationFhirMapper.class),
            mock(ConditionFhirMapper.class),
            mock(MedicationRequestFhirMapper.class),
            auditService
        );
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("everythingForPatient throws 405 when the feature flag is off")
    void flagOffThrows405() {
        properties.getEverything().setEnabled(false);
        assertThatThrownBy(() -> service.everythingForPatient(patientId))
            .isInstanceOf(MethodNotAllowedException.class);
        verify(patientRepository, never()).findById(any());
    }

    @Test
    @DisplayName("everythingForPatient throws 403 when there is no active hospital context")
    void noHospitalContextThrows403() {
        properties.getEverything().setEnabled(true);
        HospitalContextHolder.setContext(HospitalContext.empty());
        assertThatThrownBy(() -> service.everythingForPatient(patientId))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(patientRepository, never()).findById(any());
    }

    @Test
    @DisplayName("everythingForPatient throws 404 when the Patient row doesn't exist")
    void unknownPatientThrows404() {
        properties.getEverything().setEnabled(true);
        setActiveHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.everythingForPatient(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(registrationRepository, never()).findByPatientIdAndHospitalId(any(), any());
    }

    @Test
    @DisplayName("everythingForPatient throws 404 when the Patient exists but is NOT registered at the active hospital")
    void crossTenantPatientThrows404WithoutRenderingPhi() {
        properties.getEverything().setEnabled(true);
        setActiveHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(new Patient()));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, activeHospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.everythingForPatient(patientId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not found at the active hospital scope");

        // CRITICAL: the Patient mapper must NEVER fire for a cross-
        // tenant request — that's the PHI-leak surface the gate
        // closes. If this verify fails, name / DOB / address /
        // phone / email crossed the tenant boundary.
        verify(patientMapper, never()).toFhir(any(Patient.class));
    }

    @Test
    @DisplayName("fullRecordForDownload bypasses the everything flag — but NOT the hospital-scope gate")
    void downloadBypassesTheFlagButNotTheScopeGate() {
        // Flag OFF: the FHIR-facing operation is closed, yet the portal
        // download must still work. Getting ForbiddenOperationException
        // (the NEXT gate) instead of MethodNotAllowedException proves the
        // flag was bypassed while the tenancy contract stayed intact.
        properties.getEverything().setEnabled(false);
        HospitalContextHolder.setContext(HospitalContext.empty());
        assertThatThrownBy(() -> service.fullRecordForDownload(patientId))
            .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    @DisplayName("fullRecordForDownload still collapses a cross-tenant patient to 404 with zero PHI rendered")
    void downloadStillEnforcesTheRegistrationGate() {
        properties.getEverything().setEnabled(false);
        setActiveHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(new Patient()));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, activeHospitalId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.fullRecordForDownload(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(patientMapper, never()).toFhir(any(Patient.class));
    }

    // Happy-path "registered patient renders the Bundle" is covered
    // end-to-end by PatientEverythingEnabledIT — exercising it via
    // mocks here would require stubbing every per-resource page query
    // (Encounter / vital / lab / condition / prescription) to return
    // an empty Page, which adds noise without security value. The
    // four cases above pin the security branches that the High
    // Copilot finding cares about.

    private void setActiveHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId)
            .build());
    }
}
