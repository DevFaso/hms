package com.example.hms.service.impl;

import com.example.hms.enums.TreatmentConsentMethod;
import com.example.hms.enums.TreatmentConsentSource;
import com.example.hms.enums.TreatmentConsentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientTreatmentConsent;
import com.example.hms.payload.dto.TreatmentConsentRequestDTO;
import com.example.hms.payload.dto.TreatmentConsentResponseDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientTreatmentConsentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Consent-to-treat (P3 #21): the first such record in the system — the
 * portal's consent checkbox was accepted and discarded until V126. Record,
 * not gate; revoke, never delete; ELECTRONIC captures carry a digest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TreatmentConsentServiceImplTest {

    @Mock private PatientTreatmentConsentRepository consentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private TreatmentConsentServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("CHU Ouaga");

        patientId = UUID.randomUUID();
        patient = Patient.builder()
            .firstName("Awa").lastName("Kaboré")
            .dateOfBirth(LocalDate.of(1990, 5, 1))
            .build();
        patient.setId(patientId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(consentRepository.save(any(PatientTreatmentConsent.class))).thenAnswer(i -> i.getArgument(0));
        when(staffRepository.findByUserIdAndHospitalId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    }

    private TreatmentConsentRequestDTO electronic() {
        return TreatmentConsentRequestDTO.builder()
            .method(TreatmentConsentMethod.ELECTRONIC)
            .signedName("Awa Kaboré")
            .build();
    }

    @Test
    void electronicConsentCarriesADigest() {
        TreatmentConsentResponseDTO created =
            service.record(patientId, hospitalId, null, TreatmentConsentSource.CHECK_IN, electronic());

        assertThat(created.getStatus()).isEqualTo(TreatmentConsentStatus.ACTIVE);
        assertThat(created.getSource()).isEqualTo(TreatmentConsentSource.CHECK_IN);
        assertThat(created.getSignatureHash()).hasSize(64).matches("[0-9a-f]+");
        assertThat(created.getSignedName()).isEqualTo("Awa Kaboré");
    }

    @Test
    void verbalConsentHasNoDigest() {
        TreatmentConsentRequestDTO verbal = TreatmentConsentRequestDTO.builder()
            .method(TreatmentConsentMethod.VERBAL)
            .build();

        TreatmentConsentResponseDTO created =
            service.record(patientId, hospitalId, null, TreatmentConsentSource.MANUAL, verbal);

        assertThat(created.getSignatureHash()).isNull();
        assertThat(created.getMethod()).isEqualTo(TreatmentConsentMethod.VERBAL);
    }

    @Test
    void recordRequiresAHospitalScope() {
        TreatmentConsentRequestDTO request = electronic();

        assertThatThrownBy(() -> service.record(patientId, null, null,
            TreatmentConsentSource.MANUAL, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void recordRefusesAnUnregisteredPatient() {
        UUID foreignHospitalId = UUID.randomUUID();
        Hospital foreign = new Hospital();
        foreign.setId(foreignHospitalId);
        when(hospitalRepository.findById(foreignHospitalId)).thenReturn(Optional.of(foreign));
        TreatmentConsentRequestDTO request = electronic();

        assertThatThrownBy(() -> service.record(patientId, foreignHospitalId, null,
            TreatmentConsentSource.MANUAL, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered");
    }

    @Test
    void appointmentOfAnotherPatientIsRefused() {
        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        Patient other = Patient.builder().firstName("X").lastName("Y").build();
        other.setId(UUID.randomUUID());
        appointment.setPatient(other);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        TreatmentConsentRequestDTO request = electronic();
        request.setAppointmentId(appointment.getId());

        assertThatThrownBy(() -> service.record(patientId, hospitalId, null,
            TreatmentConsentSource.MANUAL, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("different patient");
    }

    @Test
    void listIs404ForAScopedCallerWithoutRegistration() {
        UUID foreignScope = UUID.randomUUID();

        // Same message as unknown-patient: the caller learns nothing.
        assertThatThrownBy(() -> service.getForPatient(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Patient not found");
    }

    @Test
    void listMapsRowsNewestFirst() {
        PatientTreatmentConsent row = PatientTreatmentConsent.builder()
            .patient(patient).hospital(hospital)
            .method(TreatmentConsentMethod.ELECTRONIC)
            .source(TreatmentConsentSource.PRE_CHECK_IN)
            .status(TreatmentConsentStatus.ACTIVE)
            .signedName("Awa Kaboré")
            .consentedAt(java.time.LocalDateTime.now())
            .build();
        row.setId(UUID.randomUUID());
        when(consentRepository.findForPatient(patientId, hospitalId)).thenReturn(List.of(row));

        List<TreatmentConsentResponseDTO> list = service.getForPatient(patientId, hospitalId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getHospitalName()).isEqualTo("CHU Ouaga");
        assertThat(list.get(0).getSource()).isEqualTo(TreatmentConsentSource.PRE_CHECK_IN);
    }

    @Test
    void revokeDemandsAReason() {
        PatientTreatmentConsent row = activeRow();
        when(consentRepository.findById(row.getId())).thenReturn(Optional.of(row));
        UUID rowId = row.getId();

        assertThatThrownBy(() -> service.revoke(rowId, hospitalId, null, " "))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("revocation reason");
    }

    @Test
    void revokeStampsAndNeverDeletes() {
        PatientTreatmentConsent row = activeRow();
        when(consentRepository.findById(row.getId())).thenReturn(Optional.of(row));
        UUID actor = UUID.randomUUID();

        TreatmentConsentResponseDTO revoked =
            service.revoke(row.getId(), hospitalId, actor, "Patient withdrew consent");

        assertThat(revoked.getStatus()).isEqualTo(TreatmentConsentStatus.REVOKED);
        assertThat(row.getRevokedAt()).isNotNull();
        assertThat(row.getRevokedByUserId()).isEqualTo(actor);
        assertThat(row.getRevocationReason()).isEqualTo("Patient withdrew consent");
    }

    @Test
    void doubleRevokeIsRefused() {
        PatientTreatmentConsent row = activeRow();
        row.setStatus(TreatmentConsentStatus.REVOKED);
        when(consentRepository.findById(row.getId())).thenReturn(Optional.of(row));
        UUID rowId = row.getId();

        assertThatThrownBy(() -> service.revoke(rowId, hospitalId, null, "again"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been revoked");
    }

    @Test
    void revokeIs404ForAForeignScope() {
        PatientTreatmentConsent row = activeRow();
        when(consentRepository.findById(row.getId())).thenReturn(Optional.of(row));
        UUID rowId = row.getId();
        UUID foreignScope = UUID.randomUUID();

        assertThatThrownBy(() -> service.revoke(rowId, foreignScope, null, "reason"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Consent record not found");
    }

    @Test
    void hasActiveConsentDelegatesToTheRepository() {
        when(consentRepository.existsByPatient_IdAndHospital_IdAndStatus(
            patientId, hospitalId, TreatmentConsentStatus.ACTIVE)).thenReturn(true);

        assertThat(service.hasActiveConsent(patientId, hospitalId)).isTrue();
        assertThat(service.hasActiveConsent(null, hospitalId)).isFalse();
    }

    private PatientTreatmentConsent activeRow() {
        PatientTreatmentConsent row = PatientTreatmentConsent.builder()
            .patient(patient).hospital(hospital)
            .method(TreatmentConsentMethod.ELECTRONIC)
            .source(TreatmentConsentSource.CHECK_IN)
            .status(TreatmentConsentStatus.ACTIVE)
            .consentedAt(java.time.LocalDateTime.now())
            .build();
        row.setId(UUID.randomUUID());
        return row;
    }
}
