package com.example.hms.fhir.write;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.FhirWriteProperties;
import com.example.hms.fhir.mapper.PatientFhirMapper;
import com.example.hms.fhir.mapper.PatientFhirMapper.MrnIdentifier;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FHIR {@code PUT /Patient/{id}} and the conditional {@code POST /Patient} are
 * anchored on the caller's active hospital, and a patient (or MRN) that lives
 * only at another hospital answers exactly like one that does not exist.
 *
 * <p>Every refusal test asks the SAME question in two worlds (the subject
 * exists at another hospital, or it exists nowhere) and requires the answers
 * to be identical in status AND body, the OperationOutcome HAPI renders.
 * Comparing status alone would pass a 404 whose wording still told the two
 * worlds apart.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientFhirWriteServiceTenancyTest {

    private static final FhirContext FHIR = FhirContext.forR4();
    private static final String MRN = "MRN-0042";

    @Mock private PatientFhirMapper patientMapper;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private PatientFhirWriteService service;
    private final UUID callerHospital = UUID.randomUUID();
    private final UUID otherHospital = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        FhirWriteProperties properties = new FhirWriteProperties();
        properties.setEnabled(true);
        service = new PatientFhirWriteService(
            properties, patientMapper, patientRepository, registrationRepository, auditEventLogService);
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    /* ── PUT /Patient/{id} ─────────────────────────────────────────────── */

    @Test
    void putUpdatesAPatientRegisteredAtTheCallersHospital() {
        scope(callerHospital);
        Patient mine = patient();
        when(registrationRepository.existsByPatientIdAndHospitalId(mine.getId(), callerHospital)).thenReturn(true);
        when(patientRepository.findById(mine.getId())).thenReturn(Optional.of(mine));

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThat(service.update(mine.getId(), body)).isSameAs(mine);

        verify(patientMapper).applyFhirUpdates(mine, body);
        verify(patientRepository).save(mine);
    }

    @Test
    void putOnAnotherHospitalsPatientIsByteIdenticalToAMissingId() {
        scope(callerHospital);
        UUID patientId = UUID.randomUUID();
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, callerHospital)).thenReturn(false);

        // World 1: the id is a real patient, registered only at another hospital.
        // findById answering it is what TenantAwareJpaRepository does for a
        // super-admin, or for a caller also permitted at that other hospital.
        Patient foreign = patient();
        foreign.setId(patientId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(foreign));
        Throwable elsewhere = catchThrowable(() -> service.update(patientId, new org.hl7.fhir.r4.model.Patient()));

        // World 2: the same id exists nowhere.
        Mockito.reset(patientRepository);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        Throwable nowhere = catchThrowable(() -> service.update(patientId, new org.hl7.fhir.r4.model.Patient()));

        assertIdenticalRefusal(elsewhere, nowhere);
        assertThat(nowhere).isInstanceOf(ResourceNotFoundException.class);
        verify(patientMapper, never()).applyFhirUpdates(any(), any());
        verify(patientRepository, never()).save(any());
    }

    @Test
    void putAsksOnlyTheRegistrationWhenThePatientIsNotTheCallers() {
        // Cost parity: a foreign id must not cost a second query that a missing
        // id does not; the registration is the one question asked.
        scope(callerHospital);
        UUID patientId = UUID.randomUUID();
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, callerHospital)).thenReturn(false);

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThatThrownBy(() -> service.update(patientId, body)).isInstanceOf(ResourceNotFoundException.class);
        verify(patientRepository, never()).findById(any());
    }

    @Test
    void putWithoutAHospitalScopeIsForbidden() {
        HospitalContextHolder.setContext(HospitalContext.builder().build());
        UUID patientId = UUID.randomUUID();

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThatThrownBy(() -> service.update(patientId, body)).isInstanceOf(ForbiddenOperationException.class);
        verify(registrationRepository, never()).existsByPatientIdAndHospitalId(any(), any());
        verify(patientRepository, never()).save(any());
    }

    /* ── POST /Patient + If-None-Exist ─────────────────────────────────── */

    @Test
    void conditionalCreateReturnsTheCallersOwnPatient() {
        scope(callerHospital);
        Patient mine = patient();
        stubMrnToken(callerHospital);
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(callerHospital, MRN))
            .thenReturn(List.of(registration(mine)));

        assertThat(service.conditionalCreate(ifNoneExist(callerHospital), new org.hl7.fhir.r4.model.Patient()))
            .isSameAs(mine);
    }

    @Test
    void conditionalCreateNamingAnotherHospitalIsByteIdenticalToAnUnmatchedMrn() {
        scope(callerHospital);
        stubMrnToken(otherHospital);
        String header = ifNoneExist(otherHospital);

        // World 1: the MRN is live at the other hospital.
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(otherHospital, MRN))
            .thenReturn(List.of(registration(patient())));
        Throwable elsewhere = catchThrowable(() ->
            service.conditionalCreate(header, new org.hl7.fhir.r4.model.Patient()));

        // World 2: it matches nothing anywhere.
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(otherHospital, MRN))
            .thenReturn(List.of());
        Throwable nowhere = catchThrowable(() ->
            service.conditionalCreate(header, new org.hl7.fhir.r4.model.Patient()));

        assertIdenticalRefusal(elsewhere, nowhere);
        assertThat(nowhere).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void conditionalCreateWithoutAHospitalScopeIsForbidden() {
        HospitalContextHolder.setContext(HospitalContext.builder().build());
        String header = ifNoneExist(otherHospital);
        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();

        assertThatThrownBy(() -> service.conditionalCreate(header, body))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(registrationRepository, never()).findActiveByHospitalIdAndIdentifier(any(), any());
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    /** Same exception type, same HTTP status, same message, same rendered OperationOutcome. */
    private static void assertIdenticalRefusal(Throwable first, Throwable second) {
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isExactlyInstanceOf(second.getClass());
        BaseServerResponseException a = (BaseServerResponseException) first;
        BaseServerResponseException b = (BaseServerResponseException) second;
        assertThat(a.getStatusCode()).isEqualTo(b.getStatusCode());
        assertThat(a.getMessage()).isEqualTo(b.getMessage());
        assertThat(FHIR.newJsonParser().encodeResourceToString(a.getOperationOutcome()))
            .isEqualTo(FHIR.newJsonParser().encodeResourceToString(b.getOperationOutcome()));
    }

    private void stubMrnToken(UUID hospitalInToken) {
        when(patientMapper.parseMrnSearchToken(any()))
            .thenReturn(Optional.of(new MrnIdentifier(hospitalInToken, MRN)));
    }

    private static String ifNoneExist(UUID hospitalId) {
        return "identifier=urn:hms:hospital:" + hospitalId + ":mrn|" + MRN;
    }

    private static PatientHospitalRegistration registration(Patient patient) {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setMrn(MRN);
        registration.setPatient(patient);
        return registration;
    }

    private static Patient patient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        return patient;
    }

    private static void scope(UUID hospitalId) {
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).build());
    }
}
