package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.FamilyHistoryMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientFamilyHistory;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;
import com.example.hms.repository.FamilyHistoryRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.FamilyHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FamilyHistoryServiceImpl implements FamilyHistoryService {
    private static final String FAMILY_HISTORY_NOT_FOUND_PREFIX = "Family history not found with id: ";


    private final FamilyHistoryRepository familyHistoryRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final FamilyHistoryMapper familyHistoryMapper;

    @Override
    public FamilyHistoryResponseDTO createFamilyHistory(FamilyHistoryRequestDTO requestDTO) {
        log.info("Creating family history for patient: {}", requestDTO.getPatientId());

        Patient patient = patientRepository.findById(requestDTO.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + requestDTO.getPatientId()));

        Hospital hospital = hospitalRepository.findById(requestDTO.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + requestDTO.getHospitalId()));

        Staff recordedBy = null;
        if (requestDTO.getRecordedByStaffId() != null) {
            recordedBy = staffRepository.findById(requestDTO.getRecordedByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getRecordedByStaffId()));
        }

        PatientFamilyHistory familyHistory = familyHistoryMapper.toEntity(requestDTO, patient, hospital, recordedBy);
        PatientFamilyHistory savedHistory = familyHistoryRepository.save(familyHistory);

        log.info("Family history created with id: {}", savedHistory.getId());
        return familyHistoryMapper.toResponseDTO(savedHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public FamilyHistoryResponseDTO getFamilyHistoryById(UUID id) {
        log.debug("Fetching family history with id: {}", id);
        
        PatientFamilyHistory familyHistory = familyHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAMILY_HISTORY_NOT_FOUND_PREFIX + id));

        return familyHistoryMapper.toResponseDTO(familyHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyHistoryResponseDTO> getFamilyHistoriesByPatientId(UUID patientId) {
        log.debug("Fetching family histories for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientFamilyHistory> histories = familyHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId);
        
        return histories.stream()
                .map(familyHistoryMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyHistoryResponseDTO> getGeneticConditions(UUID patientId) {
        log.debug("Fetching genetic conditions for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientFamilyHistory> geneticConditions = 
                familyHistoryRepository.findByPatient_IdAndGeneticConditionTrueOrderByRecordedDateDesc(patientId);
        
        return geneticConditions.stream()
                .map(familyHistoryMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyHistoryResponseDTO> getScreeningRecommendations(UUID patientId) {
        log.debug("Fetching screening recommendations for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientFamilyHistory> screeningNeeded = 
                familyHistoryRepository.findByPatient_IdAndScreeningRecommendedTrueOrderByRecordedDateDesc(patientId);
        
        return screeningNeeded.stream()
                .map(familyHistoryMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyHistoryResponseDTO> getFamilyHistoriesByConditionCategory(UUID patientId, String category) {
        log.debug("Fetching family histories by category {} for patient: {}", category, patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientFamilyHistory> histories = 
                familyHistoryRepository.findByPatient_IdAndConditionCategoryOrderByRecordedDateDesc(patientId, category);
        
        return histories.stream()
                .map(familyHistoryMapper::toResponseDTO)
                .toList();
    }

    @Override
    public FamilyHistoryResponseDTO updateFamilyHistory(UUID id, FamilyHistoryRequestDTO requestDTO) {
        log.info("Updating family history with id: {}", id);

        PatientFamilyHistory existingHistory = familyHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAMILY_HISTORY_NOT_FOUND_PREFIX + id));

        // Update staff if changed
        if (requestDTO.getRecordedByStaffId() != null && 
            (existingHistory.getRecordedBy() == null || 
             !existingHistory.getRecordedBy().getId().equals(requestDTO.getRecordedByStaffId()))) {
            Staff recordedBy = staffRepository.findById(requestDTO.getRecordedByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getRecordedByStaffId()));
            existingHistory.setRecordedBy(recordedBy);
        }

        familyHistoryMapper.updateEntity(existingHistory, requestDTO);
        PatientFamilyHistory updatedHistory = familyHistoryRepository.save(existingHistory);

        log.info("Family history updated successfully: {}", id);
        return familyHistoryMapper.toResponseDTO(updatedHistory);
    }

    @Override
    public void deleteFamilyHistory(UUID id) {
        log.info("Deleting family history with id: {}", id);

        PatientFamilyHistory familyHistory = familyHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(FAMILY_HISTORY_NOT_FOUND_PREFIX + id));

        familyHistory.setActive(false);
        familyHistoryRepository.save(familyHistory);

        log.info("Family history soft deleted: {}", id);
    }
}
