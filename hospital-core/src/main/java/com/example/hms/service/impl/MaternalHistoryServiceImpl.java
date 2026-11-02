package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MaternalHistoryMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.utility.GestationalDating;
import com.example.hms.utility.ObstetricEligibility;
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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;

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
        private static final String PATIENT_NOT_FOUND_WITH_ID = "patient.notFoundWithId";
        private static final String HOSPITAL_NOT_FOUND_WITH_ID = "hospital.notFoundWithId";
        private static final String USER_NOT_FOUND_WITH_USERNAME = "user.notFoundByUsername";
        private static final String STAFF_NOT_FOUND_FOR_USER_AT_HOSPITAL = "staff.notFoundForUserHospital";

    private final MaternalHistoryRepository maternalHistoryRepository;
    private final PatientRepository patientRepository;
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final MaternalHistoryMapper maternalHistoryMapper;
    /** QA D1067: whether the pregnancy a history dates has ended in a delivery. */
    private final com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;

    @Override
    public MaternalHistoryResponseDTO createMaternalHistory(MaternalHistoryRequestDTO request, String username) {
        log.info("Creating maternal history for patient ID: {}", request.getPatientId());
        requireHistoryContent(request);
        
        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_WITH_ID, request.getPatientId()));
        // QA D103 (D46 family): a maternal history is an obstetric record.
        ObstetricEligibility.requireObstetricEligible(patient);
        
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId()));
        // QA D401: written at the caller's acting hospital (the body's id is a
        // claim), for a patient registered there.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId());
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        // Check if maternal history already exists for this patient
        if (maternalHistoryRepository.existsByPatient_Id(request.getPatientId())) {
            // QA D826: translated, 409 on patientId, so the form can offer to
            // open the existing record instead of a dead-end English toast.
            throw new ConflictException("patientId:maternalHistory.alreadyExists");
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
        requireConsistentObstetricCounts(maternalHistory);
        deriveEstimatedDueDate(maternalHistory);
        
        MaternalHistory saved = maternalHistoryRepository.save(maternalHistory);
        
        log.info("Created maternal history ID: {} (version {}) for patient ID: {}", 
                saved.getId(), saved.getVersionNumber(), patient.getId());
        
        return toDto(saved);
    }

    /**
     * QA D1148 class (round 21): a history records something. QA D1182 class
     * (round 22): an edit writes a NEW version from the request alone, so the
     * edit is held to the same rule as the create - a blank edit saved an
     * empty version over the recorded history.
     */
    static void requireHistoryContent(MaternalHistoryRequestDTO request) {
        com.example.hms.service.support.ClinicalContent.requireAnyAnswer(request, java.util.Set.of("patientId",
            "hospitalId", "recordedByStaffId", "recordedDate", "updateReason"), "clinicalNotes",
            "maternalHistory.contentRequired");
    }

    @Override
    public MaternalHistoryResponseDTO updateMaternalHistory(UUID id, MaternalHistoryRequestDTO request, String username) {
        log.info("Updating maternal history ID: {}", id);
        
        MaternalHistory existingHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(existingHistory.getHospital(), MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id);
        requireHistoryContent(request);
        
        Patient patient = patientRepository.findById(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_WITH_ID, request.getPatientId()));
        // QA D103 (D46 family): a maternal history is an obstetric record.
        ObstetricEligibility.requireObstetricEligible(patient);
        
        Hospital hospital = hospitalRepository.findClinicalById(request.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId()));
        // QA r5 tenancy sweep: the new version is written at the caller's acting hospital; the body's id is a claim.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId());
        
        // Ensure patient ID matches
        if (!existingHistory.getPatient().getId().equals(request.getPatientId())) {
            throw new BusinessException("maternalHistory.patientChange");
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
        requireConsistentObstetricCounts(newVersion);
        deriveEstimatedDueDate(newVersion);
        
        MaternalHistory saved = maternalHistoryRepository.save(newVersion);
        
        log.info("Created maternal history version {} (ID: {}) for patient ID: {}", 
                saved.getVersionNumber(), saved.getId(), patient.getId());
        
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MaternalHistoryResponseDTO getMaternalHistoryById(UUID id, String username) {
        log.debug("Retrieving maternal history ID: {}", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        
        return toDto(maternalHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public MaternalHistoryResponseDTO getCurrentMaternalHistoryByPatientId(UUID patientId, String username) {
        log.debug("Retrieving current maternal history for patient ID: {}", patientId);
        
        // E9 #59d — the current history across the readable hospitals when the
        // caller acts in one; a super-admin in global view keeps the unscoped read.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            // QA D1082: no history yet is an answer (204), not an error.
            return maternalHistoryRepository.findCurrentByPatientId(patientId).map(this::toDto).orElse(null);
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<MaternalHistory> current = maternalHistoryRepository
            .findFirstByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(patientId, readable).stream().toList();
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(current.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital maternal history read on the treatment relationship");
        // QA D1082: every chart of a woman with no history asked this and got
        // a 404; none is an answer, which the controller sends as 204.
        return current.stream()
            .map(this::toDto)
            .findFirst()
            .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaternalHistoryResponseDTO> getAllVersionsByPatientId(UUID patientId, String username) {
        log.debug("Retrieving all maternal history versions for patient ID: {}", patientId);
        
        // E9 #59d — every version across the readable hospitals when the caller acts in one.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            return maternalHistoryRepository.findAllVersionsByPatientId(patientId).stream()
                .map(this::toDto)
                .toList();
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<MaternalHistory> histories = maternalHistoryRepository
            .findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(patientId, readable);
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(histories.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital maternal history read on the treatment relationship");
        return histories.stream()
                .map(this::toDto)
                .toList();
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
        
        return toDto(maternalHistory);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaternalHistoryResponseDTO> getMaternalHistoryByDateRange(
            UUID patientId, LocalDateTime startDate, LocalDateTime endDate, String username) {
        log.debug("Retrieving maternal history for patient {} between {} and {}", patientId, startDate, endDate);
        
        List<MaternalHistory> histories = maternalHistoryRepository
                .findByPatientIdAndDateRange(patientId, startDate, endDate);
        
        return histories.stream()
                .map(this::toDto)
                .toList();
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
        
        return histories.map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getHighRiskMaternalHistory(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving high-risk maternal history for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository.findHighRiskByHospital(hospitalId, pageable);
        
        return histories.map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getPendingReview(UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving pending review maternal history for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository.findPendingReviewByHospital(hospitalId, pageable);
        
        return histories.map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getRequiringSpecialistReferral(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving maternal history requiring specialist referral for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository
                .findRequiringSpecialistReferral(hospitalId, pageable);
        
        return histories.map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MaternalHistoryResponseDTO> getWithPsychosocialConcerns(
            UUID hospitalId, Pageable pageable, String username) {
        log.debug("Retrieving maternal history with psychosocial concerns for hospital ID: {}", hospitalId);
        
        Page<MaternalHistory> histories = maternalHistoryRepository
                .findWithPsychosocialConcerns(hospitalId, pageable);
        
        return histories.map(this::toDto);
    }

    @Override
    public MaternalHistoryResponseDTO markAsReviewed(UUID id, String username) {
        log.info("Marking maternal history ID: {} as reviewed", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(maternalHistory.getHospital(), MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id);
        
        maternalHistory.setReviewedByProvider(true);
        maternalHistory.setReviewTimestamp(LocalDateTime.now());
        
        MaternalHistory updated = maternalHistoryRepository.save(maternalHistory);
        
        log.info("Marked maternal history ID: {} as reviewed by user: {}", id, username);
        
        return toDto(updated);
    }

    @Override
    public void deleteMaternalHistory(UUID id, String username) {
        log.warn("Deleting maternal history ID: {}", id);
        
        MaternalHistory maternalHistory = maternalHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id));
        // QA r5 tenancy sweep: a write loads under the tenant guard.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(maternalHistory.getHospital(), MATERNAL_HISTORY_NOT_FOUND_WITH_ID, id);
        
        // QA D930 class: a hard delete of a colleague's record was open to anyone at
        // the hospital. Only the clinician who recorded it (or a super-admin in
        // global view) deletes it; everyone else updates it, which keeps a version.
        if (maternalHistory.getRecordedBy() != null) {
            // QA D1112: the authorship rule, not "act in your own name".
            com.example.hms.security.CallerIdentity.requireAuthorUnlessSuperAdmin(maternalHistory.getRecordedBy(),
                "maternalHistory.delete.recorderOnly");
        }
        maternalHistoryRepository.delete(maternalHistory);
        
        log.warn("Deleted maternal history ID: {} by user: {}", id, username);
    }

    /**
     * QA D809: "G1 P3" was saved. The counts of a new record or a new version
     * must describe one possible history; the refusal names the form field.
     */
    private static void requireConsistentObstetricCounts(MaternalHistory history) {
        com.example.hms.utility.ObstetricCounts.requireConsistent(
            history.getGravida(), history.getPara(), history.getAbortions(), "obstetricHistory.");
    }

    /**
     * QA D162 (D104 family): the due date was never derived, so the labour
     * episode's gestational age stayed blank. When the clinician gives an LMP
     * that can date a current pregnancy and no due date, Naegele's rule fills it.
     * An explicit due date always wins.
     */
    private void deriveEstimatedDueDate(MaternalHistory history) {
        if (history.getEstimatedDueDate() != null
            || !GestationalDating.isPlausibleCurrentLmp(history.getLastMenstrualPeriod(), java.time.LocalDate.now())) {
            return;
        }
        history.setEstimatedDueDate(GestationalDating.dueDateFromLmp(
            history.getLastMenstrualPeriod(), history.getMenstrualCycleLengthDays()));
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
        
        return toDto(saved);
    }

    /**
     * QA D1067 (caused by the D1005 fix): the one way this service maps a
     * history, so every read says whether its pregnancy is over. A woman
     * delivered on 30 October still showed "Grossesse en cours : 39 SA" on the
     * chart and a running term on the maternity list: the history kept its
     * dates and nothing said a delivery had followed them. The delivery is the
     * latest one recorded at or after the pregnancy's start (the LMP, else the
     * due date less a term by Naegele's rule, else the history's own date) -
     * the rule the prenatal schedule already refuses a delivered pregnancy by.
     */
    private MaternalHistoryResponseDTO toDto(MaternalHistory history) {
        MaternalHistoryResponseDTO dto = maternalHistoryMapper.toResponseDTO(history);
        if (dto == null || history == null || history.getPatient() == null || history.getPatient().getId() == null) {
            return dto;
        }
        LocalDateTime since = pregnancyStart(history);
        // Read at the history's own hospital: a delivery another hospital
        // recorded is that hospital's record, not disclosed through this one.
        UUID hospitalId = history.getHospital() != null ? history.getHospital().getId() : null;
        if (since != null && hospitalId != null) {
            deliveryRecordRepository
                .findFirstByPatient_IdAndHospital_IdAndBirthDateTimeGreaterThanEqualOrderByBirthDateTimeDesc(
                    history.getPatient().getId(), hospitalId, since)
                .ifPresent(delivery -> dto.setDeliveredAt(delivery.getBirthDateTime()));
        }
        return dto;
    }

    private static LocalDateTime pregnancyStart(MaternalHistory history) {
        if (history.getLastMenstrualPeriod() != null) {
            return history.getLastMenstrualPeriod().atStartOfDay();
        }
        java.time.LocalDate due = history.getEstimatedDueDateByUltrasound() != null
            ? history.getEstimatedDueDateByUltrasound() : history.getEstimatedDueDate();
        if (due != null) {
            return due.minusDays(GestationalDating.TERM_DAYS).atStartOfDay();
        }
        return history.getRecordedDate();
    }
}
