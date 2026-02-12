package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.HospitalMapper;
import com.example.hms.mapper.PatientConsentMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientConsentServiceImplTest {

    @Mock private PatientConsentRepository consentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private PatientConsentMapper consentMapper;
    @Mock private PatientMapper patientMapper;
    @Mock private HospitalMapper hospitalMapper;
    @Mock private AuditEventLogRepository auditRepository;

    @InjectMocks private PatientConsentServiceImpl service;

    // ---------- grantConsent ----------

    @Test
    void grantConsent_newConsent() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(toHospId);
        request.setPurpose("Treatment");
        request.setConsentExpiration(LocalDateTime.now().plusDays(30));

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
        Hospital fromHosp = Hospital.builder().name("From").build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().name("To").build();
        toHosp.setId(toHospId);

        PatientConsent newConsent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        newConsent.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(fromHosp));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(toHosp));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, fromHospId)).thenReturn(true);
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.empty());
        when(consentMapper.toEntity(eq(request), eq(patient), eq(fromHosp), eq(toHosp))).thenReturn(newConsent);
        when(consentRepository.save(any(PatientConsent.class))).thenReturn(newConsent);
        when(patientMapper.toPatientDTO(eq(patient), eq(fromHospId), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(fromHosp)).thenReturn(HospitalResponseDTO.builder().build());
        when(hospitalMapper.toHospitalDTO(toHosp)).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        PatientConsentResponseDTO result = service.grantConsent(request);
        assertThat(result).isNotNull();
        verify(consentRepository).save(any(PatientConsent.class));
    }

    @Test
    void grantConsent_patientNotFound() {
        UUID patientId = UUID.randomUUID();
        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(UUID.randomUUID());
        request.setToHospitalId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsent(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void grantConsent_notRegistered() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(toHospId);

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(Hospital.builder().build()));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(Hospital.builder().build()));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, fromHospId)).thenReturn(false);

        assertThatThrownBy(() -> service.grantConsent(request))
            .isInstanceOf(IllegalStateException.class);
    }

    // ---------- revokeConsent ----------

    @Test
    void revokeConsent_success() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        User user = User.builder().build();
        user.setId(UUID.randomUUID());
        Patient patient = Patient.builder().user(user).build();
        patient.setId(patientId);
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(fromHospId);

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).consentGiven(true).build();
        consent.setId(UUID.randomUUID());

        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.of(consent));

        service.revokeConsent(patientId, fromHospId, toHospId);

        assertThat(consent.isConsentGiven()).isFalse();
        verify(consentRepository).save(consent);
    }

    @Test
    void revokeConsent_notFound() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeConsent(patientId, fromHospId, toHospId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getAllConsents ----------

    @Test
    void getAllConsents_returnsPage() {
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(UUID.randomUUID());
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(UUID.randomUUID());

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());

        Page<PatientConsent> page = new PageImpl<>(List.of(consent));
        when(consentRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        Page<PatientConsentResponseDTO> result = service.getAllConsents(PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);
    }

    // ---------- isConsentActive ----------

    @Test
    void isConsentActive_true() {
        UUID pid = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        PatientConsent consent = PatientConsent.builder().consentGiven(true).build();
        consent.setId(UUID.randomUUID());

        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(pid, from, to))
            .thenReturn(Optional.of(consent));

        boolean result = service.isConsentActive(pid, from, to);
        assertThat(result).isTrue();
    }

    @Test
    void isConsentActive_noConsent() {
        UUID pid = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(pid, from, to))
            .thenReturn(Optional.empty());

        boolean result = service.isConsentActive(pid, from, to);
        assertThat(result).isFalse();
    }
}
