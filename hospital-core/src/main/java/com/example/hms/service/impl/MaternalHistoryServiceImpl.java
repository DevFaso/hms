package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MaternalHistoryMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.MaternalHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service implementation for managing maternal history documentation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MaternalHistoryServiceImpl implements MaternalHistoryService {

        private static final String MATERNAL_HISTORY_NOT_FOUND_WITH_ID = "maternalHistory.notFoundById";
        private static final String MATERNAL_HISTORY_VERSION_NOT_FOUND = "maternalHistory.versionNotFound";
        private static final String MATERNAL_HISTORY_NOT_FOUND_FOR_PATIENT = "maternalHistory.noneForPatient";
        private static final String PATIENT_NOT_FOUND_WITH_ID = "patient.notFoundWithId";
        private static final String HOSPITAL_NOT_FOUND_WITH_ID = "hospital.notFoundWithId";
        private static final String USER_NOT_FOUND_WITH_USERNAME = "user.notFoundByUsername";
        private static final String STAFF_NOT_FOUND_FOR_USER_AT_HOSPITAL = "staff.notFoundForUserHospital";

    private final MaternalHistoryRepository maternalHistoryRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final MaternalHistoryMapper maternalHistoryMapper;

    @Override
    public MaternalHistoryResponseDTO createMaternalHistory(MaternalHistoryRequestDTO request, String username) {
        log.info("Creating maternal history for patient ID: {}", request.getPatientId());
        
        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_WITH_ID, request.getPatientId()));
        
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId()));
        
        // Check if maternal history already exists for this patient
        if (maternalHistoryRepository.existsByPatient_Id(request.getPatientId())) {
            throw new BusinessException("Maternal history already exists for patient. Use update to create a new version.");
        }
        
        MaternalHistory maternalHistory = new MaternalHistory();
        maternalHistory.setPatient(patient);
        maternalHistory.setHospital(hospital);
        maternalHistory.setVersionNumber(1);
        maternalHistory.setRecordedDate(LocalDateTime.now());
        
        // Find staff by username and hospital
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_WITH_USERNAME, username));
        Staff staff = staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        STAFF_NOT_FOUND_FOR_USER_AT_HOSPITAL, username, hospital.getId()));
        maternalHistory.setRecordedBy(staff);
        
        maternalHistoryMapper.updateEntityFromRequest(maternalHistory, request);
        
        MaternalHistory saved = maternalHistoryRepository.save(maternalHistory);
        
        log.info("Created maternal history ID: {} (version {}) for patient ID: {}", 
                saved.getId(), saved.getVersionNumber(), patient.getId());
        
        return maternalHistoryMapper.toResponseDTO(saved);
    }

    @Override
    public MaternalHistoryResponseDTO updateMaternalHistory(UUID id, MaternalHistoryRequestDTO request, String username) {
        log.info("Updating maternal history ID: {}", id);
        
        MaternalHistory existingHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_WITH_ID, request.getPatientId()));
        
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId()));
        
        // Ensure patient ID matches
        if (!existingHistory.getPatient().getId().equals(request.getPatientId())) {
            throw new BusinessException("Cannot change patient ID when updating maternal history");
        }
        
        // Get next version number
        Integer maxVersion = maternalHistoryRepository.findMaxVersionByPatientId(patient.getId());
        Integer nextVersion = (maxVersion != null) ? maxVersion + 1 : 1;
        
        // Create new version
        MaternalHistory newVersion = new MaternalHistory();
        newVersion.setPatient(patient);
        newVersion.setHospital(hospital);
        newVersion.setVersionNumber(nextVersion);
        newVersion.setRecordedDate(LocalDateTime.now());
        
        // Find staff by username and hospital
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_WITH_USERNAME, username));
        Staff staff = staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        STAFF_NOT_FOUND_FOR_USER_AT_HOSPITAL, username, hospital.getId()));
        newVersion.setRecordedBy(staff);
        
        maternalHistoryMapper.updateEntityFromRequest(newVersion, request);
        
        MaternalHistory saved = maternalHistoryRepository.save(newVersion);
        
        log.info("Created maternal history version {} (ID: {}) for patient ID: {}", 
                saved.getVersionNumber(), saved.getId(), patient.getId());
        
        return maternalHistoryMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MaternalHistoryResponseDTO getMaternalHistoryById(UUID id, String username) {
        log.debug("Retrieving maternal history ID: {}", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        return maternalHistoryMapper.toResponseDTO(maternalHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public MaternalHistoryResponseDTO getCurrentMaternalHistoryByPatientId(UUID patientId, String username) {
        log.debug("Retrieving current maternal history for patient ID: {}", patientId);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findCurrentByPatientId(patientId)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_FOR_PATIENT, patientId));
        
        return maternalHistoryMapper.toResponseDTO(maternalHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaternalHistoryResponseDTO> getAllVersionsByPatientId(UUID patientId, String username) {
        log.debug("Retrieving all maternal history versions for patient ID: {}", patientId);
        
        List<MaternalHistory> histories = maternalHistoryRepository.findAllVersionsByPatientId(patientId);
        
        return histories.stream()
                .map(maternalHistoryMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public MaternalHistoryResponseDTO getMaternalHistoryByPatientIdAndVersion(
            UUID patientId, Integer versionNumber, String username) {
        log.debug("Retrieving maternal history version {} for patient ID: {}", versionNumber, patientId);
        
        MaternalHistory maternalHistory = maternalHistoryRepository
                .findByPatientIdAndVersion(patientId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        MATERNAL_HISTORY_VERSION_NOT_FOUND, versionNumber, patientId));
        
        return maternalHistoryMapper.toResponseDTO(maternalHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaternalHistoryResponseDTO> getMaternalHistoryByDateRange(
            UUID patientId, LocalDateTime startDate, LocalDateTime endDate, String username) {
        log.debug("Retrieving maternal history for patient {} between {} and {}", patientId, startDate, endDate);
        
        List<MaternalHistory> histories = maternalHistoryRepository
                .findByPatientIdAndDateRange(patientId, startDate, endDate);
        
        return histories.stream()
                .map(maternalHistoryMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> searchMaternalHistory(
            UUID hospitalId, UUID patientId, String riskCategory, Boolean dataComplete,
            Boolean reviewedByProvider, LocalDateTime dateFrom, LocalDateTime dateTo,
            Pageable pageable, String username) {
        log.debug("Searching maternal history with filters");
        
        Page<MaternalHistory> histories = maternalHistoryRepository.searchMaternalHistory(
                hospitalId, patientId, riskCategory, dataComplete, reviewedByProvider,
                dateFrom, dateTo, pageable);
        
        return histories.map(maternalHistoryMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getHighRiskMaternalHistory(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving high-risk maternal history for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository.findHighRiskByHospital(hospitalId, pageable);
        
        return histories.map(maternalHistoryMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getPendingReview(UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving pending review maternal history for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository.findPendingReviewByHospital(hospitalId, pageable);
        
        return histories.map(maternalHistoryMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getRequiringSpecialistReferral(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving maternal history requiring specialist referral for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository
                .findRequiringSpecialistReferral(hospitalId, pageable);
        
        return histories.map(maternalHistoryMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getWithPsychosocialConcerns(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving maternal history with psychosocial concerns for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository
                .findWithPsychosocialConcerns(hospitalId, pageable);
        
        return histories.map(maternalHistoryMapper::toResponseDTO);
    }

    @Override
    public MaternalHistoryResponseDTO markAsReviewed(UUID id, String username) {
        log.info("Marking maternal history ID: {} as reviewed", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        maternalHistory.setReviewedByProvider(true);
        maternalHistory.setReviewTimestamp(LocalDateTime.now());
        
        MaternalHistory updated = maternalHistoryRepository.save(maternalHistory);
        
        log.info("Marked maternal history ID: {} as reviewed by user: {}", id, username);
        
        return maternalHistoryMapper.toResponseDTO(updated);
    }

    @Override
    public void deleteMaternalHistory(UUID id, String username) {
        log.warn("Deleting maternal history ID: {}", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        maternalHistoryRepository.delete(maternalHistory);
        
        log.warn("Deleted maternal history ID: {} by user: {}", id, username);
    }

    @Override
    public MaternalHistoryResponseDTO calculateRiskScore(UUID id, String username) {
        log.info("Calculating risk score for maternal history ID: {}", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        // Simple risk calculation based on history flags
        int riskScore = 0;
        String riskCategory = "LOW";
        
        if (Boolean.TRUE.equals(maternalHistory.getGestationalDiabetesHistory())) riskScore += 10;
        if (Boolean.TRUE.equals(maternalHistory.getPreeclampsiaHistory())) riskScore += 15;
        if (Boolean.TRUE.equals(maternalHistory.getEclampsiaHistory())) riskScore += 20;
        if (Boolean.TRUE.equals(maternalHistory.getHellpSyndromeHistory())) riskScore += 20;
        if (Boolean.TRUE.equals(maternalHistory.getPretermLaborHistory())) riskScore += 10;
        if (Boolean.TRUE.equals(maternalHistory.getPostpartumHemorrhageHistory())) riskScore += 10;
        if (Boolean.TRUE.equals(maternalHistory.getDiabetes())) riskScore += 15;
        if (Boolean.TRUE.equals(maternalHistory.getHypertension())) riskScore += 15;
        if (Boolean.TRUE.equals(maternalHistory.getCardiacDisease())) riskScore += 20;
        if (Boolean.TRUE.equals(maternalHistory.getRenalDisease())) riskScore += 15;
        
        if (riskScore >= 50) {
            riskCategory = "HIGH";
        } else if (riskScore >= 25) {
            riskCategory = "MODERATE";
        }
        
        maternalHistory.setRiskCategory(riskCategory);
        
        MaternalHistory saved = maternalHistoryRepository.save(maternalHistory);
        
        return maternalHistoryMapper.toResponseDTO(saved);
    }
}
