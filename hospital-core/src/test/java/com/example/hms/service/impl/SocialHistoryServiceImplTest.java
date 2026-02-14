package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.SocialHistoryMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.SocialHistoryRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialHistoryServiceImplTest {

    @Mock private SocialHistoryRepository socialHistoryRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private SocialHistoryMapper socialHistoryMapper;

    @InjectMocks
    private SocialHistoryServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private UUID staffId;
    private UUID historyId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        historyId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        staff = new Staff();
        staff.setId(staffId);
    }

    @Test
    void createSocialHistory_success() {
        SocialHistoryRequestDTO dto = new SocialHistoryRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);
        dto.setRecordedByStaffId(staffId);
        dto.setActive(true);

        PatientSocialHistory entity = new PatientSocialHistory();
        entity.setId(historyId);
        SocialHistoryResponseDTO responseDTO = new SocialHistoryResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(socialHistoryRepository.findByPatient_IdAndActiveTrue(patientId)).thenReturn(List.of());
        when(socialHistoryRepository.countByPatient_Id(patientId)).thenReturn(0L);
        when(socialHistoryMapper.toEntity(any(), eq(patient), eq(hospital), eq(staff))).thenReturn(entity);
        when(socialHistoryRepository.save(entity)).thenReturn(entity);
        when(socialHistoryMapper.toResponseDTO(entity)).thenReturn(responseDTO);

        SocialHistoryResponseDTO result = service.createSocialHistory(dto);

        assertThat(result).isEqualTo(responseDTO);
        verify(socialHistoryRepository).save(entity);
    }

    @Test
    void createSocialHistory_patientNotFound_throws() {
        SocialHistoryRequestDTO dto = new SocialHistoryRequestDTO();
        dto.setPatientId(patientId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSocialHistory(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSocialHistoryById_found() {
        PatientSocialHistory entity = new PatientSocialHistory();
        entity.setId(historyId);
        SocialHistoryResponseDTO responseDTO = new SocialHistoryResponseDTO();

        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.of(entity));
        when(socialHistoryMapper.toResponseDTO(entity)).thenReturn(responseDTO);

        assertThat(service.getSocialHistoryById(historyId)).isEqualTo(responseDTO);
    }

    @Test
    void getSocialHistoryById_notFound_throws() {
        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSocialHistoryById(historyId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSocialHistoriesByPatientId_patientNotFound_throws() {
        when(patientRepository.existsById(patientId)).thenReturn(false);

        assertThatThrownBy(() -> service.getSocialHistoriesByPatientId(patientId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getSocialHistoriesByPatientId_returnsList() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        PatientSocialHistory entity = new PatientSocialHistory();
        when(socialHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId)).thenReturn(List.of(entity));
        SocialHistoryResponseDTO dto = new SocialHistoryResponseDTO();
        when(socialHistoryMapper.toResponseDTO(entity)).thenReturn(dto);

        List<SocialHistoryResponseDTO> result = service.getSocialHistoriesByPatientId(patientId);

        assertThat(result).hasSize(1);
    }

    @Test
    void getCurrentSocialHistory_patientNotFound_throws() {
        when(patientRepository.existsById(patientId)).thenReturn(false);

        assertThatThrownBy(() -> service.getCurrentSocialHistory(patientId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getCurrentSocialHistory_noCurrent_returnsNull() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(socialHistoryRepository.findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patientId))
                .thenReturn(Optional.empty());

        assertThat(service.getCurrentSocialHistory(patientId)).isNull();
    }

    @Test
    void updateSocialHistory_notFound_throws() {
        SocialHistoryRequestDTO dto = new SocialHistoryRequestDTO();
        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSocialHistory(historyId, dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateSocialHistory_success() {
        SocialHistoryRequestDTO dto = new SocialHistoryRequestDTO();
        PatientSocialHistory existing = new PatientSocialHistory();
        existing.setId(historyId);
        SocialHistoryResponseDTO responseDTO = new SocialHistoryResponseDTO();

        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.of(existing));
        when(socialHistoryRepository.save(existing)).thenReturn(existing);
        when(socialHistoryMapper.toResponseDTO(existing)).thenReturn(responseDTO);

        SocialHistoryResponseDTO result = service.updateSocialHistory(historyId, dto);

        assertThat(result).isEqualTo(responseDTO);
        verify(socialHistoryMapper).updateEntity(existing, dto);
    }

    @Test
    void deleteSocialHistory_softDeletes() {
        PatientSocialHistory entity = new PatientSocialHistory();
        entity.setId(historyId);
        entity.setActive(true);

        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.of(entity));
        when(socialHistoryRepository.save(entity)).thenReturn(entity);

        service.deleteSocialHistory(historyId);

        assertThat(entity.getActive()).isFalse();
        verify(socialHistoryRepository).save(entity);
    }

    @Test
    void deleteSocialHistory_notFound_throws() {
        when(socialHistoryRepository.findById(historyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSocialHistory(historyId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createSocialHistory_deactivatesPreviousRecords() {
        SocialHistoryRequestDTO dto = new SocialHistoryRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);
        dto.setActive(true);

        PatientSocialHistory previousHistory = new PatientSocialHistory();
        previousHistory.setActive(true);
        PatientSocialHistory newEntity = new PatientSocialHistory();
        newEntity.setId(historyId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(socialHistoryRepository.findByPatient_IdAndActiveTrue(patientId)).thenReturn(List.of(previousHistory));
        when(socialHistoryRepository.countByPatient_Id(patientId)).thenReturn(1L);
        when(socialHistoryMapper.toEntity(any(), eq(patient), eq(hospital), isNull())).thenReturn(newEntity);
        when(socialHistoryRepository.save(any())).thenReturn(newEntity);
        when(socialHistoryMapper.toResponseDTO(newEntity)).thenReturn(new SocialHistoryResponseDTO());

        service.createSocialHistory(dto);

        assertThat(previousHistory.getActive()).isFalse();
    }
}
