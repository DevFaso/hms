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

import static org.assertj.core.api.Assertions.assertThat;
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
    private com.example.hms.repository.EncounterRepository encounterRepository;
    private PatientVitalSignRepository vitalSignRepository;
    private LabResultRepository labResultRepository;
    private PrescriptionRepository prescriptionRepository;
    private com.example.hms.repository.PatientUploadedDocumentRepository uploadedDocumentRepository;
    private com.example.hms.repository.UserRepository userRepository;
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
        encounterRepository = mock(com.example.hms.repository.EncounterRepository.class);
        vitalSignRepository = mock(PatientVitalSignRepository.class);
        labResultRepository = mock(LabResultRepository.class);
        prescriptionRepository = mock(PrescriptionRepository.class);
        uploadedDocumentRepository = mock(com.example.hms.repository.PatientUploadedDocumentRepository.class);
        userRepository = mock(com.example.hms.repository.UserRepository.class);
        patientMapper = mock(PatientFhirMapper.class);
        auditService = mock(AuditEventLogService.class);
        service = new PatientEverythingService(
            properties,
            patientRepository,
            registrationRepository,
            encounterRepository,
            vitalSignRepository,
            labResultRepository,
            mock(PatientProblemRepository.class),
            prescriptionRepository,
            uploadedDocumentRepository,
            mock(com.example.hms.repository.DischargeSummaryRepository.class),
            userRepository,
            patientMapper,
            mock(EncounterFhirMapper.class),
            mock(ObservationFhirMapper.class),
            mock(ConditionFhirMapper.class),
            mock(MedicationRequestFhirMapper.class),
            mock(com.example.hms.fhir.mapper.DocumentReferenceFhirMapper.class),
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

    @Test
    @DisplayName("fullRecordForDownload FAILS when the page cap is hit — never a silent partial file")
    void downloadFailsInsteadOfReturningAPartialFile() {
        // A partial export that looks complete is the worst outcome for a
        // file someone treats as "the whole record". Encounters here always
        // report another page, so the loop can never finish.
        setActiveHospital();
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(new Patient()));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, activeHospitalId))
            .thenReturn(Optional.of(new com.example.hms.model.PatientHospitalRegistration()));
        when(encounterRepository.findByPatient_IdAndHospital_Id(
                org.mockito.ArgumentMatchers.eq(patientId),
                org.mockito.ArgumentMatchers.eq(activeHospitalId),
                any(org.springframework.data.domain.PageRequest.class)))
            .thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(new com.example.hms.model.Encounter()),
                inv.getArgument(2), Long.MAX_VALUE));
        stubEmptyPage();

        assertThatThrownBy(() -> service.fullRecordForDownload(patientId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("40-page safety limit");
    }

    @Test
    @DisplayName("the export audit carries the authenticated actor, not SYSTEM")
    void exportActorResolvesFromTheSecurityContext() {
        com.example.hms.model.User exporter = new com.example.hms.model.User();
        exporter.setId(UUID.randomUUID());
        exporter.setUsername("dr.awa");
        when(userRepository.findByUsernameIgnoreCase("dr.awa"))
            .thenReturn(Optional.of(exporter));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("dr.awa", "n/a", java.util.List.of()));
        try {
            assertThat(service.resolveExportActor())
                .as("the PHI-export trail must name the clinician who downloaded")
                .isSameAs(exporter);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // Happy-path "registered patient renders the Bundle" is covered
    // end-to-end by PatientEverythingEnabledIT — exercising it via
    // mocks here would require stubbing every per-resource page query
    // (Encounter / vital / lab / condition / prescription) to return
    // an empty Page, which adds noise without security value. The
    // four cases above pin the security branches that the High
    // Copilot finding cares about.

    /** The other paged sections return empty pages so only Encounters drive the cursor. */
    private void stubEmptyPage() {
        org.mockito.stubbing.Answer<Object> empty = inv -> new org.springframework.data.domain.PageImpl<>(
            java.util.List.of(), inv.getArgument(2), 0);
        when(vitalSignRepository.findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
            any(), any(), any())).thenAnswer(empty);
        when(labResultRepository.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(
            any(), any(), any())).thenAnswer(empty);
        when(prescriptionRepository.findByPatient_IdAndHospital_Id(
            any(), any(), any(org.springframework.data.domain.PageRequest.class))).thenAnswer(empty);
        when(uploadedDocumentRepository.findByPatient_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            any(), any())).thenAnswer(inv -> new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(), inv.getArgument(1), 0));
    }

    private void setActiveHospital() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId)
            .build());
    }
}
