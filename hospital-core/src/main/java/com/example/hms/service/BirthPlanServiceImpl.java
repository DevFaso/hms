package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.BirthPlanMapper;
import com.example.hms.model.BirthPlan;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.BirthPlanProviderReviewRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import com.example.hms.repository.BirthPlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;

/**
 * Service implementation for Birth Plan operations.
 */
@Service
@RequiredArgsConstructor
public class BirthPlanServiceImpl implements BirthPlanService {

    private static final Logger log = LoggerFactory.getLogger(BirthPlanServiceImpl.class);

    private final BirthPlanRepository birthPlanRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final BirthPlanMapper birthPlanMapper;
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final com.example.hms.utility.RoleValidator roleValidator;

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_MIDWIFE = "ROLE_MIDWIFE";
    private static final String ROLE_NURSE = "ROLE_NURSE";
    private static final String ROLE_PATIENT = "ROLE_PATIENT";

    @Override
    @Transactional
    public BirthPlanResponseDTO createBirthPlan(BirthPlanRequestDTO request, String username) {
        User user = getUserOrThrow(username);
        
        // Determine patient and hospital
        Patient patient;
        Hospital hospital;

        if (isPatientOnly(user)) {
            // Patient creating their own birth plan
            patient = getPatientByUserOrThrow(user);
            hospital = determineHospitalForPatient(patient, request.getHospitalId());
        } else {
            // Provider creating on behalf of patient
            checkProviderAccess(user);
            patient = getPatientByIdOrThrow(request.getPatientId());
            hospital = getHospitalByIdOrThrow(request.getHospitalId());
        }

        // Create birth plan
        BirthPlan birthPlan = new BirthPlan();
        birthPlan.setPatient(patient);
        birthPlan.setHospital(hospital);
        birthPlan.setCreatedAt(LocalDateTime.now());
        birthPlan.setProviderReviewRequired(true);

        // Map request to entity
        birthPlanMapper.updateEntityFromRequest(birthPlan, request);

        // Save
        BirthPlan saved = birthPlanRepository.save(birthPlan);
        log.info("Created birth plan ID {} for patient {}", saved.getId(), patient.getId());

        return birthPlanMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public BirthPlanResponseDTO updateBirthPlan(UUID id, BirthPlanRequestDTO request, String username) {
        User user = getUserOrThrow(username);
        BirthPlan birthPlan = getBirthPlanInReach(user, id);

        // Update entity
        birthPlanMapper.updateEntityFromRequest(birthPlan, request);
        birthPlan.setUpdatedAt(LocalDateTime.now());

        // If plan was previously reviewed and now being updated, reset review status
        if (Boolean.TRUE.equals(birthPlan.getProviderReviewed())) {
            log.info("Resetting provider review status for birth plan {} after update", id);
            birthPlan.setProviderReviewed(false);
            birthPlan.setProviderSignature(null);
            birthPlan.setProviderSignatureDate(null);
            birthPlan.setProviderComments(null);
        }

        BirthPlan updated = birthPlanRepository.save(birthPlan);
        log.info("Updated birth plan ID {}", id);

        return birthPlanMapper.toResponseDTO(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public BirthPlanResponseDTO getBirthPlanById(UUID id, String username) {
        User user = getUserOrThrow(username);
        BirthPlan birthPlan = getBirthPlanInReach(user, id);

        return birthPlanMapper.toResponseDTO(birthPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BirthPlanResponseDTO> getBirthPlansByPatientId(UUID patientId, String username) {
        User user = getUserOrThrow(username);
        requirePatientInReach(user, patientId);

        // E9 #59d — birth plans follow the patient across the readable
        // hospitals when the caller acts in one; a super-admin in global view
        // (and a patient reading their own) keeps the unscoped read.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            return birthPlanRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(birthPlanMapper::toResponseDTO)
                .toList();
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<BirthPlan> birthPlans = birthPlanRepository.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable);
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(birthPlans.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital birth plan read on the treatment relationship");
        return birthPlans.stream()
            .map(birthPlanMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BirthPlanResponseDTO getActiveBirthPlan(UUID patientId, String username) {
        User user = getUserOrThrow(username);
        requirePatientInReach(user, patientId);

        // E9 #59d — the most recent plan across the readable hospitals.
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        UUID actingHospitalId = ctx.pinnedHospitalId();
        if (actingHospitalId == null) {
            return birthPlanRepository.findActiveBirthPlanByPatientId(patientId)
                .map(birthPlanMapper::toResponseDTO)
                .orElse(null);
        }
        UUID requesterUserId = ctx.getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, actingHospitalId);
        List<BirthPlan> active = birthPlanRepository.findFirstByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(patientId, readable).stream().toList();
        reachRecorder.recordReach(patientId, actingHospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(active.stream().map(r -> CrossHospitalReachRecorder.hospitalIdOf(r.getHospital())).toList(), actingHospitalId),
            "Cross-hospital birth plan read on the treatment relationship");
        return active.stream()
            .map(birthPlanMapper::toResponseDTO)
            .findFirst()
            .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BirthPlanResponseDTO> searchBirthPlans(
        UUID hospitalId,
        UUID patientId,
        Boolean providerReviewed,
        LocalDate dueDateFrom,
        LocalDate dueDateTo,
        Pageable pageable,
        String username
    ) {
        User user = getUserOrThrow(username);

        // Check access - only providers can search across patients
        checkProviderAccess(user);

        Page<BirthPlan> birthPlans = birthPlanRepository.searchBirthPlans(
            hospitalId,
            patientId,
            providerReviewed,
            dueDateFrom,
            dueDateTo,
            pageable
        );

        return birthPlans.map(birthPlanMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public BirthPlanResponseDTO providerReview(UUID id, BirthPlanProviderReviewRequestDTO review, String username) {
        User user = getUserOrThrow(username);

        // Only providers can review, and only at the hospital they act at
        checkProviderReviewAccess(user);
        BirthPlan birthPlan = requireAtActingHospital(getBirthPlanByIdOrThrow(id));

        // Update review fields
        birthPlan.setProviderReviewed(review.getReviewed());
        birthPlan.setProviderSignature(review.getSignature());
        birthPlan.setProviderSignatureDate(LocalDateTime.now());
        birthPlan.setProviderComments(review.getComments());
        birthPlan.setUpdatedAt(LocalDateTime.now());

        BirthPlan reviewed = birthPlanRepository.save(birthPlan);
        log.info("Provider {} reviewed birth plan ID {}", username, id);

        return birthPlanMapper.toResponseDTO(reviewed);
    }

    @Override
    @Transactional
    public void deleteBirthPlan(UUID id, String username) {
        User user = getUserOrThrow(username);
        BirthPlan birthPlan = getBirthPlanInReach(user, id);

        birthPlanRepository.delete(birthPlan);
        log.info("Deleted birth plan ID {} by user {}", id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BirthPlanResponseDTO> getPendingReviews(UUID hospitalId, Pageable pageable, String username) {
        User user = getUserOrThrow(username);

        // Only providers can view pending reviews
        checkProviderReviewAccess(user);

        if (hospitalId == null) {
            throw new BusinessException("Hospital ID is required to view pending reviews");
        }

        Page<BirthPlan> pendingPlans = birthPlanRepository.findPendingReviewByHospital(hospitalId, pageable);
        return pendingPlans.map(birthPlanMapper::toResponseDTO);
    }

    // Helper methods

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("user.notFoundByUsername", username));
    }

    private Patient getPatientByIdOrThrow(UUID patientId) {
        return patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
    }

    private Patient getPatientByUserOrThrow(User user) {
        return patientRepository.findByUserId(user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFoundForUser", user.getUsername()));
    }

    private Hospital getHospitalByIdOrThrow(UUID hospitalId) {
        return hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
    }

    private BirthPlan getBirthPlanByIdOrThrow(UUID id) {
        return birthPlanRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("birthPlan.notFound", id));
    }

    private boolean hasRole(User user, String roleCode) {
        return user.getUserRoles() != null && user.getUserRoles().stream()
            .anyMatch(userRole -> roleCode.equals(userRole.getRole().getCode()));
    }

    /**
     * A patient-only caller holds {@code ROLE_PATIENT} and none of the
     * provider roles. The patient role used to be asked FIRST, so a doctor,
     * midwife or nurse who is also a patient was held to their own birth plans
     * — and, creating one, filed it for themselves instead of the patient.
     */
    private boolean isPatientOnly(User user) {
        return hasRole(user, ROLE_PATIENT) && !isProvider(user);
    }

    private boolean isProvider(User user) {
        return hasRole(user, ROLE_SUPER_ADMIN) || hasRole(user, ROLE_DOCTOR)
            || hasRole(user, ROLE_MIDWIFE) || hasRole(user, ROLE_NURSE);
    }

    /**
     * The birth plan, when this caller may reach it. A caller who is neither a
     * provider nor the patient is refused before the lookup (the answer does
     * not depend on the id). A patient-only caller's refusal for another
     * patient's plan is the missing-id answer, not a 403: a 403 for a real id
     * beside a 404 for a made-up one told a patient which ids exist.
     * E9 #67 (D5): a hospital admin is refused at the controller; only the
     * platform operator bypasses the per-role checks.
     */
    private BirthPlan getBirthPlanInReach(User user, UUID id) {
        boolean patientOnly = isPatientOnly(user);
        if (!patientOnly && !isProvider(user)) {
            throw new AccessDeniedException("You do not have permission to access this birth plan");
        }
        BirthPlan birthPlan = getBirthPlanByIdOrThrow(id);
        if (patientOnly && !ownsPatient(user, birthPlan.getPatient() != null ? birthPlan.getPatient().getId() : null)) {
            throw new ResourceNotFoundException("birthPlan.notFound", id);
        }
        return patientOnly ? birthPlan : requireAtActingHospital(birthPlan);
    }

    /**
     * A provider reaches a birth plan only at the hospital they act at;
     * another hospital's plan answers exactly as a missing one. A null scope
     * (a super-admin in global view) reaches any.
     */
    private BirthPlan requireAtActingHospital(BirthPlan birthPlan) {
        UUID scope = roleValidator.requireActiveHospitalId();
        if (scope != null && (birthPlan.getHospital() == null || !scope.equals(birthPlan.getHospital().getId()))) {
            throw new ResourceNotFoundException("birthPlan.notFound", birthPlan.getId());
        }
        return birthPlan;
    }

    /**
     * The patient-id reads: a patient-only caller may name only their own row
     * and is told otherwise exactly as for an unknown id, before the patient is
     * looked up; a provider reads any, as before.
     */
    private void requirePatientInReach(User user, UUID patientId) {
        if (isPatientOnly(user)) {
            if (!ownsPatient(user, patientId)) {
                throw new ResourceNotFoundException("patient.notFound", patientId);
            }
            return;
        }
        checkProviderAccess(user);
        getPatientByIdOrThrow(patientId);
    }

    /**
     * {@code existsByIdAndUserId}, not {@code findByUserId}: the single-result
     * finder throws on a tenant left with duplicate {@code user_id} rows (a
     * 500 where this owes a decision) and decrypts a whole Patient to compare
     * two ids.
     */
    private boolean ownsPatient(User user, UUID patientId) {
        return patientId != null && patientRepository.existsByIdAndUserId(patientId, user.getId());
    }

    private void checkProviderAccess(User user) {
        if (!isProvider(user)) {
            throw new AccessDeniedException("Only healthcare providers can perform this action");
        }
    }

    private void checkProviderReviewAccess(User user) {
        if (!hasRole(user, ROLE_SUPER_ADMIN) &&
            !hasRole(user, ROLE_DOCTOR) &&
            !hasRole(user, ROLE_MIDWIFE)) {
            throw new AccessDeniedException("Only doctors and midwives can review birth plans");
        }
    }

    private Hospital determineHospitalForPatient(Patient patient, UUID requestedHospitalId) {
        if (requestedHospitalId != null) {
            return getHospitalByIdOrThrow(requestedHospitalId);
        }

        // Try to get patient's primary hospital from registrations
        if (patient.getHospitalRegistrations() != null && !patient.getHospitalRegistrations().isEmpty()) {
            return patient.getHospitalRegistrations().iterator().next().getHospital();
        }

        throw new BusinessException("No hospital specified and patient has no hospital registrations");
    }

    @SuppressWarnings("java:S1172") // user will be used when HospitalContext is implemented
    private UUID getUserHospitalId(User user) {
        // This would typically come from HospitalContext or user's assignments
        // For now, returning null to indicate it should be provided
        return null;
    }
}
