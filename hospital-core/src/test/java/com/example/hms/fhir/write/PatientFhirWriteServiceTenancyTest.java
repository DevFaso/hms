package com.example.hms.fhir.write;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.example.hms.exception.BusinessException;
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
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
 * anchored on the caller's active hospital, and a patient (or MRN) that the
 * caller's hospital does not actively hold answers exactly like one that does
 * not exist.
 *
 * <p>Every refusal test asks the SAME question in two worlds (the subject
 * exists somewhere the caller may not write, or it exists nowhere) and
 * requires the answers to be identical in status AND body, the
 * OperationOutcome HAPI renders.
 *
 * <p>The scope comes from {@code RoleValidator.requireActiveHospitalId()},
 * whose null is honoured as global view only for a verified super-admin.
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
    @Mock private RoleValidator roleValidator;

    private PatientFhirWriteService service;
    private final UUID callerHospital = UUID.randomUUID();
    private final UUID otherHospital = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        FhirWriteProperties properties = new FhirWriteProperties();
        properties.setEnabled(true);
        service = new PatientFhirWriteService(
            properties, patientMapper, patientRepository, registrationRepository, auditEventLogService, roleValidator);
        when(patientRepository.save(any(Patient.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    /* ── PUT /Patient/{id} ─────────────────────────────────────────────── */

    @Test
    void putUpdatesAPatientActivelyRegisteredAtTheCallersHospital() {
        scope(callerHospital);
        Patient mine = patient();
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(mine.getId(), callerHospital))
            .thenReturn(Optional.of(registration(mine, true)));

        org.hl7.fhir.r4.model.Patient mapped = mapsTo(mine, callerHospital);
        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThat(service.update(mine.getId(), body)).isSameAs(mapped);

        verify(patientMapper).applyFhirUpdates(mine, body);
        // Flushed before the audit, which commits on its own (REQUIRES_NEW):
        // a flush failure must not leave a SUCCESS row for a rolled-back update.
        InOrder order = Mockito.inOrder(patientRepository, auditEventLogService);
        order.verify(patientRepository).save(mine);
        order.verify(patientRepository).flush();
        order.verify(auditEventLogService).logEvent(any());
    }

    @Test
    void putOnAnotherHospitalsPatientIsByteIdenticalToAMissingId() {
        scope(callerHospital);
        UUID patientId = UUID.randomUUID();

        // World 1: the id is a real patient, registered only at another hospital.
        // findById answering it is what TenantAwareJpaRepository does for a
        // caller also permitted at that other hospital.
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
    void putOnAPatientWhoLeftTheCallersHospitalIsByteIdenticalToAMissingId() {
        // Discharged or transferred away: the registration here still exists
        // but is no longer active. It is not this hospital's to overwrite.
        scope(callerHospital);
        UUID patientId = UUID.randomUUID();
        Patient left = patient();
        left.setId(patientId);

        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, callerHospital)).thenReturn(true);
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, callerHospital))
            .thenReturn(Optional.of(registration(left, false)));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, callerHospital))
            .thenReturn(Optional.empty());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(left));
        Throwable inactive = catchThrowable(() -> service.update(patientId, new org.hl7.fhir.r4.model.Patient()));

        Mockito.reset(registrationRepository, patientRepository);
        Throwable nowhere = catchThrowable(() -> service.update(patientId, new org.hl7.fhir.r4.model.Patient()));

        assertIdenticalRefusal(inactive, nowhere);
        assertThat(nowhere).isInstanceOf(ResourceNotFoundException.class);
        verify(patientMapper, never()).applyFhirUpdates(any(), any());
    }

    @Test
    void aRefusedPutAsksOneQueryAndNeverLoadsThePatient() {
        scope(callerHospital);
        UUID patientId = UUID.randomUUID();

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThatThrownBy(() -> service.update(patientId, body)).isInstanceOf(ResourceNotFoundException.class);

        verify(registrationRepository).findByPatientIdAndHospitalIdAndActiveTrue(patientId, callerHospital);
        verify(patientRepository, never()).findById(any());
    }

    @Test
    void putWithANullScopeTheVerifiedFlagDoesNotBackIsForbidden() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
        UUID patientId = UUID.randomUUID();

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThatThrownBy(() -> service.update(patientId, body)).isInstanceOf(ForbiddenOperationException.class);
        verify(patientRepository, never()).findById(any());
        verify(patientRepository, never()).save(any());
    }

    @Test
    void putWhenNoHospitalResolvesIsA403NotA500() {
        when(roleValidator.requireActiveHospitalId()).thenThrow(new BusinessException("Hospital context required."));
        UUID patientId = UUID.randomUUID();

        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThatThrownBy(() -> service.update(patientId, body)).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void aVerifiedSuperAdminInGlobalViewIsNotScopedToTheirJwtHomeHospital() {
        // The raw HospitalContext still carries the JWT-derived home hospital
        // for a super-admin with no X-Hospital-Id. Reading it scoped a
        // global-view super-admin to that hospital; RoleValidator drops it.
        UUID homeHospital = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true).activeHospitalId(homeHospital).build());
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        Patient elsewhere = patient();
        when(patientRepository.findById(elsewhere.getId())).thenReturn(Optional.of(elsewhere));

        org.hl7.fhir.r4.model.Patient mapped = mapsTo(elsewhere, null);
        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();
        assertThat(service.update(elsewhere.getId(), body)).isSameAs(mapped);

        verify(registrationRepository, never()).findByPatientIdAndHospitalIdAndActiveTrue(any(), any());
        verify(patientRepository).save(elsewhere);
    }

    /* ── POST /Patient + If-None-Exist ─────────────────────────────────── */

    @Test
    void conditionalCreateReturnsTheCallersOwnPatient() {
        scope(callerHospital);
        Patient mine = patient();
        stubMrnToken(callerHospital);
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(callerHospital, MRN))
            .thenReturn(List.of(registration(mine, true)));
        org.hl7.fhir.r4.model.Patient mapped = mapsTo(mine, callerHospital);

        assertThat(service.conditionalCreate(ifNoneExist(callerHospital), new org.hl7.fhir.r4.model.Patient()))
            .isSameAs(mapped);
    }

    @Test
    void conditionalCreateNamingAnotherHospitalIsByteIdenticalToAnUnmatchedMrn() {
        scope(callerHospital);
        stubMrnToken(otherHospital);
        String header = ifNoneExist(otherHospital);

        // World 1: the MRN is live at the other hospital.
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(otherHospital, MRN))
            .thenReturn(List.of(registration(patient(), true)));
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
    void aVerifiedSuperAdminInGlobalViewMayNameAnyHospitalsMrn() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        Patient theirs = patient();
        stubMrnToken(otherHospital);
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(otherHospital, MRN))
            .thenReturn(List.of(registration(theirs, true)));
        org.hl7.fhir.r4.model.Patient mapped = mapsTo(theirs, null);

        assertThat(service.conditionalCreate(ifNoneExist(otherHospital), new org.hl7.fhir.r4.model.Patient()))
            .isSameAs(mapped);
    }

    @Test
    void conditionalCreateWithANullScopeTheVerifiedFlagDoesNotBackIsForbidden() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
        stubMrnToken(otherHospital);
        String header = ifNoneExist(otherHospital);
        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();

        assertThatThrownBy(() -> service.conditionalCreate(header, body))
            .isInstanceOf(ForbiddenOperationException.class);
        verify(registrationRepository, never()).findActiveByHospitalIdAndIdentifier(any(), any());
    }

    @Test
    void aMalformedRequestKeepsItsDocumented422EvenWithNoScope() {
        // Shape validation reads no data, so it runs before the scope: a
        // missing If-None-Exist is still 422, not "scope required".
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
        org.hl7.fhir.r4.model.Patient body = new org.hl7.fhir.r4.model.Patient();

        assertThatThrownBy(() -> service.conditionalCreate(null, body))
            .isInstanceOf(UnprocessableEntityException.class)
            .hasMessageContaining("If-None-Exist");
        verify(roleValidator, never()).requireActiveHospitalId();
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

    private void scope(UUID hospitalId) {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private void stubMrnToken(UUID hospitalInToken) {
        when(patientMapper.parseMrnSearchToken(any()))
            .thenReturn(Optional.of(new MrnIdentifier(hospitalInToken, MRN)));
    }

    private static String ifNoneExist(UUID hospitalId) {
        return "identifier=urn:hms:hospital:" + hospitalId + ":mrn|" + MRN;
    }

    private static PatientHospitalRegistration registration(Patient patient, boolean active) {
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setMrn(MRN);
        registration.setPatient(patient);
        registration.setActive(active);
        return registration;
    }

    /**
     * The resource the mapper produces for {@code entity} as seen from
     * {@code scope}: the service must return THIS, mapped inside its own
     * transaction, never the entity for the provider to map after the commit
     * (a LAZY registrations walk with no session - a 500 once the write was
     * done). A scoped write maps with that hospital's MRN only; only a
     * verified super-admin in global view ({@code null}) gets the unscoped form.
     */
    private org.hl7.fhir.r4.model.Patient mapsTo(Patient entity, UUID scope) {
        org.hl7.fhir.r4.model.Patient mapped = new org.hl7.fhir.r4.model.Patient();
        mapped.setId(entity.getId().toString());
        if (scope == null) {
            when(patientMapper.toFhir(entity)).thenReturn(mapped);
        } else {
            when(patientMapper.toFhir(entity, scope)).thenReturn(mapped);
        }
        return mapped;
    }

    private static Patient patient() {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        return patient;
    }
}
