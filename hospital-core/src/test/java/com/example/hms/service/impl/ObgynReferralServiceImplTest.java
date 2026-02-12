package com.example.hms.service.impl;

import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ObgynReferralMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.referral.ObgynReferral;
import com.example.hms.model.referral.ObgynReferralMessage;
import com.example.hms.payload.dto.referral.*;
import com.example.hms.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObgynReferralServiceImplTest {

    @Mock private ObgynReferralRepository referralRepository;
    @Mock private ObgynReferralMessageRepository messageRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private ObgynReferralMapper referralMapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private ObgynReferralServiceImpl service;

    private UUID patientId, hospitalId, referralId, userId;
    private Patient patient;
    private Hospital hospital;
    private User user;
    private ObgynReferral referral;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        referralId = UUID.randomUUID();
        userId = UUID.randomUUID();

        patient = new Patient(); patient.setId(patientId);
        patient.setFirstName("Jane"); patient.setLastName("Doe");
        hospital = new Hospital(); hospital.setId(hospitalId);
        user = new User(); user.setId(userId);
        user.setUsername("midwife1"); user.setFirstName("Test"); user.setLastName("User");
        user.setUserRoles(new HashSet<>());

        referral = ObgynReferral.builder()
            .patient(patient).hospital(hospital).midwife(user)
            .status(ObgynReferralStatus.SUBMITTED)
            .urgency(ObgynReferralUrgency.ROUTINE)
            .build();
        referral.setId(referralId);
    }

    @Test void createReferral_success() throws Exception {
        ObgynReferralCreateRequestDTO req = new ObgynReferralCreateRequestDTO();
        req.setPatientId(patientId); req.setHospitalId(hospitalId);
        req.setUrgency(ObgynReferralUrgency.ROUTINE);
        req.setReferralReason("Routine checkup");

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findByUsername("midwife1")).thenReturn(Optional.of(user));
        when(referralRepository.save(any())).thenAnswer(inv -> { ObgynReferral r = inv.getArgument(0); r.setId(referralId); return r; });
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ObgynReferralResponseDTO result = service.createReferral(req, "midwife1");
        assertThat(result.getId()).isEqualTo(referralId);
    }

    @Test void createReferral_patientNotFound() {
        ObgynReferralCreateRequestDTO req = new ObgynReferralCreateRequestDTO();
        req.setPatientId(patientId); req.setHospitalId(hospitalId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createReferral(req, "midwife1"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void createReferral_urgentNoAttachment_throws() {
        ObgynReferralCreateRequestDTO req = new ObgynReferralCreateRequestDTO();
        req.setPatientId(patientId); req.setHospitalId(hospitalId);
        req.setUrgency(ObgynReferralUrgency.URGENT);
        req.setAttachments(null);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(userRepository.findByUsername("midwife1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.createReferral(req, "midwife1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void getReferral_success() {
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralMapper.toResponseDTO(referral)).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).build());
        assertThat(service.getReferral(referralId).getId()).isEqualTo(referralId);
    }

    @Test void getReferral_notFound() {
        when(referralRepository.findById(referralId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getReferral(referralId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void getReferralsForPatient() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<ObgynReferral> page = new PageImpl<>(List.of(referral));
        when(referralRepository.findByPatient_Id(patientId, pageable)).thenReturn(page);
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).build());
        Page<ObgynReferralResponseDTO> result = service.getReferralsForPatient(patientId, pageable);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test void getReferralsForHospital() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<ObgynReferral> page = new PageImpl<>(List.of(referral));
        when(referralRepository.findByHospital_Id(hospitalId, pageable)).thenReturn(page);
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).build());
        assertThat(service.getReferralsForHospital(hospitalId, pageable).getTotalElements()).isEqualTo(1);
    }

    @Test void getReferralsForObgyn() {
        Pageable pageable = PageRequest.of(0, 10);
        when(referralRepository.findByObgyn_Id(userId, pageable)).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.getReferralsForObgyn(userId, pageable).getTotalElements()).isZero();
    }

    @Test void acknowledgeReferral_success() {
        ObgynReferralAcknowledgeRequestDTO req = new ObgynReferralAcknowledgeRequestDTO();
        req.setObgynUserId(userId); req.setPlanSummary("Will consult");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(userRepository.findByIdWithRolesAndProfiles(userId)).thenReturn(Optional.of(user));
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).status(ObgynReferralStatus.ACKNOWLEDGED).build());
        ObgynReferralResponseDTO result = service.acknowledgeReferral(referralId, req, "admin");
        assertThat(result.getStatus()).isEqualTo(ObgynReferralStatus.ACKNOWLEDGED);
    }

    @Test void acknowledgeReferral_noObgynId_throws() {
        ObgynReferralAcknowledgeRequestDTO req = new ObgynReferralAcknowledgeRequestDTO();
        req.setObgynUserId(null);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        assertThatThrownBy(() -> service.acknowledgeReferral(referralId, req, "admin"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void acknowledgeReferral_closedReferral_throws() {
        referral.setStatus(ObgynReferralStatus.CANCELLED);
        ObgynReferralAcknowledgeRequestDTO req = new ObgynReferralAcknowledgeRequestDTO();
        req.setObgynUserId(userId);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        assertThatThrownBy(() -> service.acknowledgeReferral(referralId, req, "admin"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void completeReferral_success() {
        ObgynReferralCompletionRequestDTO req = new ObgynReferralCompletionRequestDTO();
        req.setPlanSummary("Completed plan"); req.setUpdateCareTeam(true);
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).status(ObgynReferralStatus.COMPLETED).build());
        ObgynReferralResponseDTO result = service.completeReferral(referralId, req, "admin");
        assertThat(result.getStatus()).isEqualTo(ObgynReferralStatus.COMPLETED);
    }

    @Test void cancelReferral_success() {
        ObgynReferralCancelRequestDTO req = new ObgynReferralCancelRequestDTO();
        req.setReason("Patient transferred");
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(referralMapper.toResponseDTO(any())).thenReturn(ObgynReferralResponseDTO.builder().id(referralId).status(ObgynReferralStatus.CANCELLED).build());
        ObgynReferralResponseDTO result = service.cancelReferral(referralId, req, "admin");
        assertThat(result.getStatus()).isEqualTo(ObgynReferralStatus.CANCELLED);
    }

    @Test void cancelReferral_alreadyClosed_throws() {
        referral.setStatus(ObgynReferralStatus.COMPLETED);
        ObgynReferralCancelRequestDTO req = new ObgynReferralCancelRequestDTO();
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        assertThatThrownBy(() -> service.cancelReferral(referralId, req, "admin"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void addMessage_success() {
        ObgynReferralMessageRequestDTO req = new ObgynReferralMessageRequestDTO();
        req.setBody("Hello");
        ObgynReferralMessage msg = ObgynReferralMessage.builder().referral(referral).sender(user).body("Hello").build();
        msg.setId(UUID.randomUUID());
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(userRepository.findByUsername("midwife1")).thenReturn(Optional.of(user));
        when(messageRepository.save(any())).thenReturn(msg);
        when(referralMapper.serializeMessageAttachments(any())).thenReturn(null);
        when(referralMapper.toMessageDTO(any())).thenReturn(ObgynReferralMessageDTO.builder().body("Hello").build());
        ObgynReferralMessageDTO result = service.addMessage(referralId, req, "midwife1");
        assertThat(result.getBody()).isEqualTo("Hello");
    }

    @Test void addMessage_cancelledReferral_throws() {
        referral.setStatus(ObgynReferralStatus.CANCELLED);
        ObgynReferralMessageRequestDTO req = new ObgynReferralMessageRequestDTO();
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        assertThatThrownBy(() -> service.addMessage(referralId, req, "midwife1"))
            .isInstanceOf(BusinessException.class);
    }

    @Test void getMessages_success() {
        when(referralRepository.findById(referralId)).thenReturn(Optional.of(referral));
        when(messageRepository.findByReferral_IdOrderBySentAtAsc(referralId)).thenReturn(List.of());
        assertThat(service.getMessages(referralId)).isEmpty();
    }

    @Test void getStatusSummary() {
        when(referralRepository.countByStatus(any())).thenReturn(5L);
        when(referralRepository.countByStatusAndSlaDueAtBefore(any(), any())).thenReturn(2L);
        ReferralStatusSummaryDTO result = service.getStatusSummary();
        assertThat(result.getSubmitted()).isEqualTo(5L);
        assertThat(result.getOverdue()).isEqualTo(4L);
    }
}
