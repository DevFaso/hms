package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.MtmReviewStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.MtmReviewMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.MtmReview;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.MtmReviewRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * P-09: MTM review service.
 *
 * <p>Tenant isolation is enforced at every entry point. The pharmacist actor
 * is taken from the authenticated principal, never from the request body, to
 * prevent attribution spoofing. Polypharmacy is computed at start-time as a
 * point-in-time snapshot (active prescription count ≥ 5).
 *
 * <p>Audit events emitted:
 * <ul>
 *   <li>{@link AuditEventType#MTM_REVIEW_STARTED} on create.</li>
 *   <li>{@link AuditEventType#MTM_INTERVENTION_RECORDED} on update when an
 *       intervention summary is present.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MtmReviewServiceImpl implements MtmReviewService {

    private static final String AUDIT_ENTITY = "MTM_REVIEW";
    private static final String NOT_FOUND = "mtm.review.notfound";
    private static final int POLYPHARMACY_THRESHOLD = 5;

    private static final Set<PrescriptionStatus> ACTIVE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTIALLY_FILLED,
            PrescriptionStatus.DISPENSED
    );

    private final MtmReviewRepository reviewRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MtmReviewMapper mapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;

    @Override
    public MtmReviewResponseDTO startReview(MtmReviewRequestDTO dto) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (!activeHospitalId.equals(dto.getHospitalId())) {
            throw new BusinessException("Review hospital does not match the active hospital context");
        }

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
        User pharmacist = resolveCurrentUser();

        boolean polypharmacy = computePolypharmacyAlert(patient.getId(), hospital.getId());

        MtmReview review = MtmReview.builder()
                .patient(patient)
                .hospital(hospital)
                .pharmacistUser(pharmacist)
                .reviewDate(LocalDateTime.now())
                .chronicConditionFocus(dto.getChronicConditionFocus())
                .adherenceConcern(Boolean.TRUE.equals(dto.getAdherenceConcern()))
                .polypharmacyAlert(polypharmacy)
                .interventionSummary(dto.getInterventionSummary())
                .recommendedActions(dto.getRecommendedActions())
                .status(dto.getStatus() != null ? dto.getStatus() : MtmReviewStatus.DRAFT)
                .followUpDate(dto.getFollowUpDate())
                .build();

        MtmReview saved = reviewRepository.save(review);
        log.info("Started MTM review {} for patient {} (polypharmacy={})",
                saved.getId(), patient.getId(), polypharmacy);

        logAudit(AuditEventType.MTM_REVIEW_STARTED,
                "MTM review started for patient " + patient.getId()
                        + (polypharmacy ? " (polypharmacy alert)" : ""),
                saved.getId().toString());

        // If the caller submitted an intervention up-front, also log the intervention event.
        if (saved.getInterventionSummary() != null && !saved.getInterventionSummary().isBlank()) {
            logAudit(AuditEventType.MTM_INTERVENTION_RECORDED,
                    "MTM intervention recorded on creation for patient " + patient.getId(),
                    saved.getId().toString());
        }

        return mapper.toResponseDTO(saved);
    }

    @Override
    public MtmReviewResponseDTO updateReview(UUID id, MtmReviewRequestDTO dto) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        MtmReview existing = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (existing.getHospital() == null || !activeHospitalId.equals(existing.getHospital().getId())) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }

        boolean newInterventionRecorded =
                (existing.getInterventionSummary() == null || existing.getInterventionSummary().isBlank())
                        && dto.getInterventionSummary() != null && !dto.getInterventionSummary().isBlank();

        if (dto.getChronicConditionFocus() != null) {
            existing.setChronicConditionFocus(dto.getChronicConditionFocus());
        }
        if (dto.getAdherenceConcern() != null) {
            existing.setAdherenceConcern(dto.getAdherenceConcern());
        }
        if (dto.getInterventionSummary() != null) {
            existing.setInterventionSummary(dto.getInterventionSummary());
        }
        if (dto.getRecommendedActions() != null) {
            existing.setRecommendedActions(dto.getRecommendedActions());
        }
        if (dto.getStatus() != null) {
            existing.setStatus(dto.getStatus());
        }
        if (dto.getFollowUpDate() != null) {
            existing.setFollowUpDate(dto.getFollowUpDate());
        }

        MtmReview saved = reviewRepository.save(existing);
        if (newInterventionRecorded) {
            logAudit(AuditEventType.MTM_INTERVENTION_RECORDED,
                    "MTM intervention recorded for review " + saved.getId(),
                    saved.getId().toString());
        }
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MtmReviewResponseDTO getReview(UUID id) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        MtmReview existing = reviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (existing.getHospital() == null || !activeHospitalId.equals(existing.getHospital().getId())) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return mapper.toResponseDTO(existing);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MtmReviewResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        UUID active = roleValidator.requireActiveHospitalId();
        if (!active.equals(hospitalId)) {
            throw new BusinessException("Hospital ID does not match the active hospital context");
        }
        return reviewRepository.findByHospital_Id(active, pageable).map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MtmReviewResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID active = roleValidator.requireActiveHospitalId();
        return reviewRepository.findByPatient_IdAndHospital_Id(patientId, active, pageable)
                .map(mapper::toResponseDTO);
    }

    /** Counts active prescriptions; flags polypharmacy at ≥ 5 concurrent meds. */
    private boolean computePolypharmacyAlert(UUID patientId, UUID hospitalId) {
        long active = prescriptionRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
                .filter(p -> p.getStatus() != null && ACTIVE_STATUSES.contains(p.getStatus()))
                .map(Prescription::getMedicationCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .count();
        return active >= POLYPHARMACY_THRESHOLD;
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType(AUDIT_ENTITY)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
