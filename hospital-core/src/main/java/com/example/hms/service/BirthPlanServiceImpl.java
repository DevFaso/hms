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
import java.util.stream.Collectors;

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

    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
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

        if (hasRole(user, ROLE_PATIENT)) {
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
        BirthPlan birthPlan = getBirthPlanByIdOrThrow(id);

        // Check access
        checkBirthPlanAccess(user, birthPlan);

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
        BirthPlan birthPlan = getBirthPlanByIdOrThrow(id);

        // Check access
        checkBirthPlanAccess(user, birthPlan);

        return birthPlanMapper.toResponseDTO(birthPlan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BirthPlanResponseDTO> getBirthPlansByPatientId(UUID patientId, String username) {
        User user = getUserOrThrow(username);
        Patient patient = getPatientByIdOrThrow(patientId);

        // Check access
        if (hasRole(user, ROLE_PATIENT)) {
            Patient userPatient = getPatientByUserOrThrow(user);
            if (!userPatient.getId().equals(patientId)) {
                throw new AccessDeniedException("You can only view your own birth plans");
            }
        } else {
            checkProviderAccess(user);
        }

        List<BirthPlan> birthPlans = birthPlanRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        return birthPlans.stream()
            .map(birthPlanMapper::toResponseDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public BirthPlanResponseDTO getActiveBirthPlan(UUID patientId, String username) {
        User user = getUserOrThrow(username);
        Patient patient = getPatientByIdOrThrow(patientId);

        // Check access
        if (hasRole(user, ROLE_PATIENT)) {
            Patient userPatient = getPatientByUserOrThrow(user);
            if (!userPatient.getId().equals(patientId)) {
                throw new AccessDeniedException("You can only view your own birth plan");
            }
        } else {
            checkProviderAccess(user);
        }

        return birthPlanRepository.findActiveBirthPlanByPatientId(patientId)
            .map(birthPlanMapper::toResponseDTO)
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

        // If hospital admin, limit to their hospital
        if (hasRole(user, ROLE_HOSPITAL_ADMIN) && hospitalId == null) {
            // Get user's assigned hospital from context
            hospitalId = getUserHospitalId(user);
        }

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
        BirthPlan birthPlan = getBirthPlanByIdOrThrow(id);

        // Only providers can review
        checkProviderReviewAccess(user);

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
        BirthPlan birthPlan = getBirthPlanByIdOrThrow(id);

        // Check access
        checkBirthPlanAccess(user, birthPlan);

        birthPlanRepository.delete(birthPlan);
        log.info("Deleted birth plan ID {} by user {}", id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BirthPlanResponseDTO> getPendingReviews(UUID hospitalId, Pageable pageable, String username) {
        User user = getUserOrThrow(username);

        // Only providers can view pending reviews
        checkProviderReviewAccess(user);

        // If hospital admin, limit to their hospital
        if (hasRole(user, ROLE_HOSPITAL_ADMIN) && hospitalId == null) {
            hospitalId = getUserHospitalId(user);
        }

        if (hospitalId == null) {
            throw new BusinessException("Hospital ID is required to view pending reviews");
        }

        Page<BirthPlan> pendingPlans = birthPlanRepository.findPendingReviewByHospital(hospitalId, pageable);
        return pendingPlans.map(birthPlanMapper::toResponseDTO);
    }

    // Helper methods

    private User getUserOrThrow(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    private Patient getPatientByIdOrThrow(UUID patientId) {
        return patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));
    }

    private Patient getPatientByUserOrThrow(User user) {
        return patientRepository.findByUserId(user.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found for user: " + user.getUsername()));
    }

    private Hospital getHospitalByIdOrThrow(UUID hospitalId) {
        return hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));
    }

    private BirthPlan getBirthPlanByIdOrThrow(UUID id) {
        return birthPlanRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Birth plan not found with ID: " + id));
    }

    private boolean hasRole(User user, String roleCode) {
        return user.getUserRoles() != null && user.getUserRoles().stream()
            .anyMatch(userRole -> roleCode.equals(userRole.getRole().getCode()));
    }

    private void checkBirthPlanAccess(User user, BirthPlan birthPlan) {
        if (hasRole(user, ROLE_SUPER_ADMIN) || hasRole(user, ROLE_HOSPITAL_ADMIN)) {
            return;
        }

        if (hasRole(user, ROLE_PATIENT)) {
            Patient userPatient = getPatientByUserOrThrow(user);
            if (!userPatient.getId().equals(birthPlan.getPatient().getId())) {
                throw new AccessDeniedException("You can only access your own birth plans");
            }
        } else if (hasRole(user, ROLE_DOCTOR) || hasRole(user, ROLE_MIDWIFE) || hasRole(user, ROLE_NURSE)) {
            // Providers can access birth plans in their hospital
            // Additional logic could check if provider is assigned to patient
            return;
        } else {
            throw new AccessDeniedException("You do not have permission to access this birth plan");
        }
    }

    private void checkProviderAccess(User user) {
        if (!hasRole(user, ROLE_SUPER_ADMIN) &&
            !hasRole(user, ROLE_HOSPITAL_ADMIN) &&
            !hasRole(user, ROLE_DOCTOR) &&
            !hasRole(user, ROLE_MIDWIFE) &&
            !hasRole(user, ROLE_NURSE)) {
            throw new AccessDeniedException("Only healthcare providers can perform this action");
        }
    }

    private void checkProviderReviewAccess(User user) {
        if (!hasRole(user, ROLE_SUPER_ADMIN) &&
            !hasRole(user, ROLE_HOSPITAL_ADMIN) &&
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

    private UUID getUserHospitalId(User user) {
        // This would typically come from HospitalContext or user's assignments
        // For now, returning null to indicate it should be provided
        return null;
    }
}
