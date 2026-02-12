package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.FamilyHistoryMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;
import com.example.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FamilyHistoryServiceImplTest {

    @Mock private FamilyHistoryRepository familyHistoryRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private FamilyHistoryMapper familyHistoryMapper;

    @InjectMocks
    private FamilyHistoryServiceImpl service;

    private UUID patientId, hospitalId, staffId, historyId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        historyId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
        staff = new Staff(); staff.setId(staffId);
    }

    @Test
    void createFamilyHistory_success() {
        FamilyHistoryRequestDTO dto = new FamilyHistoryRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);
        dto.setRecordedByStaffId(staffId);

        PatientFamilyHistory entity = new PatientFamilyHistory();
        entity.setId(historyId);
        FamilyHistoryResponseDTO response = new FamilyHistoryResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(familyHistoryMapper.toEntity(any(), eq(patient), eq(hospital), eq(staff))).thenReturn(entity);
        when(familyHistoryRepository.save(entity)).thenReturn(entity);
        when(familyHistoryMapper.toResponseDTO(entity)).thenReturn(response);

        assertThat(service.createFamilyHistory(dto)).isEqualTo(response);
    }

    @Test
    void createFamilyHistory_patientNotFound() {
        FamilyHistoryRequestDTO dto = new FamilyHistoryRequestDTO();
        dto.setPatientId(patientId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createFamilyHistory(dto)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFamilyHistoryById_found() {
        PatientFamilyHistory entity = new PatientFamilyHistory();
        FamilyHistoryResponseDTO response = new FamilyHistoryResponseDTO();
        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.of(entity));
        when(familyHistoryMapper.toResponseDTO(entity)).thenReturn(response);
        assertThat(service.getFamilyHistoryById(historyId)).isEqualTo(response);
    }

    @Test
    void getFamilyHistoryById_notFound() {
        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getFamilyHistoryById(historyId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFamilyHistoriesByPatientId_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(familyHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId)).thenReturn(List.of(new PatientFamilyHistory()));
        when(familyHistoryMapper.toResponseDTO(any())).thenReturn(new FamilyHistoryResponseDTO());
        assertThat(service.getFamilyHistoriesByPatientId(patientId)).hasSize(1);
    }

    @Test
    void getFamilyHistoriesByPatientId_patientNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        assertThatThrownBy(() -> service.getFamilyHistoriesByPatientId(patientId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getGeneticConditions_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(familyHistoryRepository.findByPatient_IdAndGeneticConditionTrueOrderByRecordedDateDesc(patientId)).thenReturn(List.of());
        assertThat(service.getGeneticConditions(patientId)).isEmpty();
    }

    @Test
    void getGeneticConditions_patientNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        assertThatThrownBy(() -> service.getGeneticConditions(patientId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getScreeningRecommendations_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(familyHistoryRepository.findByPatient_IdAndScreeningRecommendedTrueOrderByRecordedDateDesc(patientId)).thenReturn(List.of());
        assertThat(service.getScreeningRecommendations(patientId)).isEmpty();
    }

    @Test
    void getScreeningRecommendations_patientNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        assertThatThrownBy(() -> service.getScreeningRecommendations(patientId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFamilyHistoriesByConditionCategory_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(familyHistoryRepository.findByPatient_IdAndConditionCategoryOrderByRecordedDateDesc(patientId, "CARDIAC")).thenReturn(List.of());
        assertThat(service.getFamilyHistoriesByConditionCategory(patientId, "CARDIAC")).isEmpty();
    }

    @Test
    void updateFamilyHistory_success() {
        PatientFamilyHistory existing = new PatientFamilyHistory();
        existing.setId(historyId);
        FamilyHistoryRequestDTO dto = new FamilyHistoryRequestDTO();
        FamilyHistoryResponseDTO response = new FamilyHistoryResponseDTO();

        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.of(existing));
        when(familyHistoryRepository.save(existing)).thenReturn(existing);
        when(familyHistoryMapper.toResponseDTO(existing)).thenReturn(response);

        assertThat(service.updateFamilyHistory(historyId, dto)).isEqualTo(response);
        verify(familyHistoryMapper).updateEntity(existing, dto);
    }

    @Test
    void updateFamilyHistory_notFound() {
        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateFamilyHistory(historyId, new FamilyHistoryRequestDTO()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteFamilyHistory_softDeletes() {
        PatientFamilyHistory entity = new PatientFamilyHistory();
        entity.setId(historyId);
        entity.setActive(true);
        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.of(entity));
        when(familyHistoryRepository.save(entity)).thenReturn(entity);

        service.deleteFamilyHistory(historyId);

        assertThat(entity.getActive()).isFalse();
    }

    @Test
    void deleteFamilyHistory_notFound() {
        when(familyHistoryRepository.findById(historyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteFamilyHistory(historyId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
