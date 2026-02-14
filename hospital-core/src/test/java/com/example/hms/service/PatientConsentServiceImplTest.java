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

    // ---------- getConsentsByPatient ----------

    @Test
    void getConsentsByPatient_returnsPage() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(UUID.randomUUID());
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(UUID.randomUUID());

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());

        Page<PatientConsent> page = new PageImpl<>(List.of(consent));
        when(consentRepository.findAllByPatientId(eq(patientId), any(Pageable.class))).thenReturn(page);
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        Page<PatientConsentResponseDTO> result = service.getConsentsByPatient(patientId, PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);
    }

    // ---------- getConsentsByFromHospital ----------

    @Test
    void getConsentsByFromHospital_returnsPage() {
        UUID fromHospId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(UUID.randomUUID());

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());

        Page<PatientConsent> page = new PageImpl<>(List.of(consent));
        when(consentRepository.findAllByFromHospitalId(eq(fromHospId), any(Pageable.class))).thenReturn(page);
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        Page<PatientConsentResponseDTO> result = service.getConsentsByFromHospital(fromHospId, PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);
    }

    // ---------- getConsentsByToHospital ----------

    @Test
    void getConsentsByToHospital_returnsPage() {
        UUID toHospId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(UUID.randomUUID());
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(toHospId);

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());

        Page<PatientConsent> page = new PageImpl<>(List.of(consent));
        when(consentRepository.findAllByToHospitalId(eq(toHospId), any(Pageable.class))).thenReturn(page);
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        Page<PatientConsentResponseDTO> result = service.getConsentsByToHospital(toHospId, PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);
    }

    // ---------- grantConsentWithDetails ----------

    @Test
    void grantConsentWithDetails_success() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPurpose("Research");
        request.setConsentExpiration(LocalDateTime.now().plusDays(90));

        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patientId);
        HospitalResponseDTO fromHospDTO = HospitalResponseDTO.builder().id(fromHospId).build();
        HospitalResponseDTO toHospDTO = HospitalResponseDTO.builder().id(toHospId).build();

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(toHospId);

        PatientConsent newConsent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        newConsent.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(fromHosp));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(toHosp));
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.empty());
        when(consentMapper.toEntity(eq(request), eq(patient), eq(fromHosp), eq(toHosp))).thenReturn(newConsent);
        when(consentRepository.save(any(PatientConsent.class))).thenReturn(newConsent);
        when(consentMapper.toDto(any(PatientConsent.class), eq(patientDTO), eq(fromHospDTO), eq(toHospDTO)))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        PatientConsentResponseDTO result = service.grantConsentWithDetails(request, patientDTO, fromHospDTO, toHospDTO);
        assertThat(result).isNotNull();
        verify(consentRepository).save(any(PatientConsent.class));
    }

    @Test
    void grantConsentWithDetails_patientNotFound_throws() {
        UUID patientId = UUID.randomUUID();
        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patientId);
        HospitalResponseDTO fromHospDTO = HospitalResponseDTO.builder().id(UUID.randomUUID()).build();
        HospitalResponseDTO toHospDTO = HospitalResponseDTO.builder().id(UUID.randomUUID()).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsentWithDetails(
            new PatientConsentRequestDTO(), patientDTO, fromHospDTO, toHospDTO))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void grantConsentWithDetails_existingConsent_updates() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPurpose("Updated purpose");
        request.setConsentExpiration(LocalDateTime.now().plusDays(60));

        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patientId);
        HospitalResponseDTO fromHospDTO = HospitalResponseDTO.builder().id(fromHospId).build();
        HospitalResponseDTO toHospDTO = HospitalResponseDTO.builder().id(toHospId).build();

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(toHospId);

        PatientConsent existingConsent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).consentGiven(false).build();
        existingConsent.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(fromHosp));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(toHosp));
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.of(existingConsent));
        when(consentRepository.save(any(PatientConsent.class))).thenReturn(existingConsent);
        when(consentMapper.toDto(any(PatientConsent.class), eq(patientDTO), eq(fromHospDTO), eq(toHospDTO)))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        service.grantConsentWithDetails(request, patientDTO, fromHospDTO, toHospDTO);

        assertThat(existingConsent.isConsentGiven()).isTrue();
        verify(consentMapper, never()).toEntity(any(), any(), any(), any());
    }

    // ---------- grantConsent with existing consent ----------

    @Test
    void grantConsent_existingConsent_updatesInstead() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(toHospId);
        request.setPurpose("Updated");

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
        Hospital fromHosp = Hospital.builder().name("From").build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().name("To").build();
        toHosp.setId(toHospId);

        PatientConsent existing = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).consentGiven(false).build();
        existing.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(fromHosp));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(toHosp));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, fromHospId)).thenReturn(true);
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.of(existing));
        when(consentRepository.save(any(PatientConsent.class))).thenReturn(existing);
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        service.grantConsent(request);

        assertThat(existing.isConsentGiven()).isTrue();
        verify(consentMapper, never()).toEntity(any(), any(), any(), any());
    }

    // ---------- audit failure handling ----------

    @Test
    void grantConsent_auditFailure_doesNotThrow() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(toHospId);
        request.setPurpose("Treatment");

        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalRegistrations(new HashSet<>());
        Hospital fromHosp = Hospital.builder().name("From").build();
        fromHosp.setId(fromHospId);
        Hospital toHosp = Hospital.builder().name("To").build();
        toHosp.setId(toHospId);

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(fromHosp));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.of(toHosp));
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, fromHospId)).thenReturn(true);
        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.empty());
        when(consentMapper.toEntity(any(), any(), any(), any())).thenReturn(consent);
        when(consentRepository.save(any(PatientConsent.class))).thenReturn(consent);
        when(auditRepository.save(any(AuditEventLog.class))).thenThrow(new RuntimeException("DB failure"));
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());

        // Should NOT throw despite audit failure
        PatientConsentResponseDTO result = service.grantConsent(request);
        assertThat(result).isNotNull();
    }

    @Test
    void revokeConsent_auditFailure_doesNotThrow() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        User user = User.builder().build();
        user.setId(UUID.randomUUID());
        Patient patient = Patient.builder().user(user).build();
        patient.setId(patientId);

        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(Hospital.builder().build()).consentGiven(true).build();
        consent.setId(UUID.randomUUID());

        when(consentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(patientId, fromHospId, toHospId))
            .thenReturn(Optional.of(consent));
        when(auditRepository.save(any(AuditEventLog.class))).thenThrow(new RuntimeException("Audit DB down"));

        // Should NOT throw despite audit failure
        service.revokeConsent(patientId, fromHospId, toHospId);

        assertThat(consent.isConsentGiven()).isFalse();
    }

    // ---------- grantConsent fromHospital/toHospital not found ----------

    @Test
    void grantConsent_fromHospitalNotFound_throws() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(UUID.randomUUID());

        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsent(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void grantConsent_toHospitalNotFound_throws() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientConsentRequestDTO request = new PatientConsentRequestDTO();
        request.setPatientId(patientId);
        request.setFromHospitalId(fromHospId);
        request.setToHospitalId(toHospId);

        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(Hospital.builder().build()));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsent(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- getConsentsByPatient empty ----------

    @Test
    void getConsentsByPatient_empty_returnsEmptyPage() {
        UUID patientId = UUID.randomUUID();
        Page<PatientConsent> page = new PageImpl<>(List.of());
        when(consentRepository.findAllByPatientId(eq(patientId), any(Pageable.class))).thenReturn(page);

        Page<PatientConsentResponseDTO> result = service.getConsentsByPatient(patientId, PageRequest.of(0, 10));
        assertThat(result.getContent()).isEmpty();
    }

    // ---------- grantConsentWithDetails fromHospital not found ----------

    @Test
    void grantConsentWithDetails_fromHospitalNotFound_throws() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();

        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patientId);
        HospitalResponseDTO fromHospDTO = HospitalResponseDTO.builder().id(fromHospId).build();
        HospitalResponseDTO toHospDTO = HospitalResponseDTO.builder().id(UUID.randomUUID()).build();

        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsentWithDetails(
            new PatientConsentRequestDTO(), patientDTO, fromHospDTO, toHospDTO))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void grantConsentWithDetails_toHospitalNotFound_throws() {
        UUID patientId = UUID.randomUUID();
        UUID fromHospId = UUID.randomUUID();
        UUID toHospId = UUID.randomUUID();

        PatientResponseDTO patientDTO = new PatientResponseDTO();
        patientDTO.setId(patientId);
        HospitalResponseDTO fromHospDTO = HospitalResponseDTO.builder().id(fromHospId).build();
        HospitalResponseDTO toHospDTO = HospitalResponseDTO.builder().id(toHospId).build();

        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(fromHospId)).thenReturn(Optional.of(Hospital.builder().build()));
        when(hospitalRepository.findById(toHospId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantConsentWithDetails(
            new PatientConsentRequestDTO(), patientDTO, fromHospDTO, toHospDTO))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
