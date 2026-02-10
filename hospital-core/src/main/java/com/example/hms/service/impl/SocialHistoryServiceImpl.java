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
import com.example.hms.service.SocialHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SocialHistoryServiceImpl implements SocialHistoryService {

    private final SocialHistoryRepository socialHistoryRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final SocialHistoryMapper socialHistoryMapper;

    @Override
    public SocialHistoryResponseDTO createSocialHistory(SocialHistoryRequestDTO requestDTO) {
        log.info("Creating social history for patient: {}", requestDTO.getPatientId());

        Patient patient = patientRepository.findById(requestDTO.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + requestDTO.getPatientId()));

        Hospital hospital = hospitalRepository.findById(requestDTO.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with id: " + requestDTO.getHospitalId()));

        Staff recordedBy = null;
        if (requestDTO.getRecordedByStaffId() != null) {
            recordedBy = staffRepository.findById(requestDTO.getRecordedByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getRecordedByStaffId()));
        }

        // If this is a new active record, deactivate previous ones
        if (requestDTO.getActive() == null || Boolean.TRUE.equals(requestDTO.getActive())) {
            socialHistoryRepository.findByPatient_IdAndActiveTrue(patient.getId())
                    .forEach(history -> {
                        history.setActive(false);
                        socialHistoryRepository.save(history);
                    });
        }

        // Determine version number
        long count = socialHistoryRepository.countByPatient_Id(patient.getId());
        if (requestDTO.getVersionNumber() == null) {
            requestDTO.setVersionNumber((int) (count + 1));
        }

        PatientSocialHistory socialHistory = socialHistoryMapper.toEntity(requestDTO, patient, hospital, recordedBy);
        PatientSocialHistory savedHistory = socialHistoryRepository.save(socialHistory);

        log.info("Social history created with id: {}", savedHistory.getId());
        return socialHistoryMapper.toResponseDTO(savedHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public SocialHistoryResponseDTO getSocialHistoryById(UUID id) {
        log.debug("Fetching social history with id: {}", id);
        
        PatientSocialHistory socialHistory = socialHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Social history not found with id: " + id));

        return socialHistoryMapper.toResponseDTO(socialHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SocialHistoryResponseDTO> getSocialHistoriesByPatientId(UUID patientId) {
        log.debug("Fetching social histories for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        List<PatientSocialHistory> histories = socialHistoryRepository.findByPatient_IdOrderByRecordedDateDesc(patientId);
        
        return histories.stream()
                .map(socialHistoryMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public SocialHistoryResponseDTO getCurrentSocialHistory(UUID patientId) {
        log.debug("Fetching current social history for patient: {}", patientId);

        if (!patientRepository.existsById(patientId)) {
            throw new ResourceNotFoundException("Patient not found with id: " + patientId);
        }

        PatientSocialHistory current = socialHistoryRepository
                .findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patientId)
                .orElse(null);

        return current != null ? socialHistoryMapper.toResponseDTO(current) : null;
    }

    @Override
    public SocialHistoryResponseDTO updateSocialHistory(UUID id, SocialHistoryRequestDTO requestDTO) {
        log.info("Updating social history with id: {}", id);

        PatientSocialHistory existingHistory = socialHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Social history not found with id: " + id));

        // Update staff if changed
        if (requestDTO.getRecordedByStaffId() != null && 
            (existingHistory.getRecordedBy() == null || 
             !existingHistory.getRecordedBy().getId().equals(requestDTO.getRecordedByStaffId()))) {
            Staff recordedBy = staffRepository.findById(requestDTO.getRecordedByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found with id: " + requestDTO.getRecordedByStaffId()));
            existingHistory.setRecordedBy(recordedBy);
        }

        socialHistoryMapper.updateEntity(existingHistory, requestDTO);
        PatientSocialHistory updatedHistory = socialHistoryRepository.save(existingHistory);

        log.info("Social history updated successfully: {}", id);
        return socialHistoryMapper.toResponseDTO(updatedHistory);
    }

    @Override
    public void deleteSocialHistory(UUID id) {
        log.info("Deleting social history with id: {}", id);

        PatientSocialHistory socialHistory = socialHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Social history not found with id: " + id));

        socialHistory.setActive(false);
        socialHistoryRepository.save(socialHistory);

        log.info("Social history soft deleted: {}", id);
    }
}
