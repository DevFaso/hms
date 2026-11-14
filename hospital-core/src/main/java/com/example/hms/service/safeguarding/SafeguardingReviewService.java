package com.example.hms.service.safeguarding;

import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.safeguarding.SafeguardingReview;
import com.example.hms.payload.dto.safeguarding.SafeguardingReviewDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.SafeguardingReviewRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * r53, provisional rule (user decision 2026-11-14, pending approval): the safeguarding reviews, read and recorded.
 *
 * <p>Who reads one: the treating clinical team (doctor, surgeon, physician, midwife, nurse) and the hospital
 * administrators, each at the review's own hospital, which is the caller's acting hospital. Anybody else is refused:
 * reception, billing, the patient, a proxy or family member, a native app, another hospital, and the platform
 * super-admin with no role at that hospital. A review of another hospital answers as unknown (404).
 *
 * <p>Who records one: a doctor, surgeon or physician of that hospital (the responsible clinician), with a documented
 * note. Recording closes the review and clears its tasks. Nothing here blocks or touches the patient's care.
 */
@Service
@RequiredArgsConstructor
public class SafeguardingReviewService {

    static final String NOT_FOUND_KEY = "safeguarding.review.notFound";
    static final String RECORDER_REQUIRED_KEY = "safeguarding.review.recorderRequired";
    static final String ALREADY_REVIEWED_KEY = "safeguarding.review.alreadyReviewed";

    private final SafeguardingReviewRepository reviewRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;

    /** The open (or recorded) reviews of the caller's acting hospital. */
    @Transactional(readOnly = true)
    public List<SafeguardingReviewDTO> listForHospital(SafeguardingReviewStatus status) {
        UUID hospitalId = requireReaderHospital();
        SafeguardingReviewStatus wanted = status == null ? SafeguardingReviewStatus.OPEN : status;
        return reviewRepository.findByHospitalIdAndStatusOrderByRaisedAtDesc(hospitalId, wanted).stream()
            .map(this::toDto).toList();
    }

    /** One patient's reviews at the caller's acting hospital (empty when there is none). */
    @Transactional(readOnly = true)
    public List<SafeguardingReviewDTO> listForPatient(UUID patientId) {
        UUID hospitalId = requireReaderHospital();
        return reviewRepository.findByPatientIdAndHospitalIdOrderByRaisedAtDesc(patientId, hospitalId).stream()
            .map(this::toDto).toList();
    }

    /** Records the documented review: the review closes and its tasks clear. */
    @Transactional
    public SafeguardingReviewDTO recordReview(UUID reviewId, String note) {
        UUID hospitalId = requireReaderHospital();
        SafeguardingReview review = reviewRepository.findById(reviewId)
            .filter(found -> hospitalId.equals(found.getHospitalId()))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, reviewId));
        UUID callerId = roleValidator.getCurrentUserId();
        if (!isRecorder(callerId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(RECORDER_REQUIRED_KEY));
        }
        if (review.getStatus() != SafeguardingReviewStatus.OPEN) {
            throw new com.example.hms.exception.ConflictException(MessageUtil.resolve(ALREADY_REVIEWED_KEY));
        }
        review.setStatus(SafeguardingReviewStatus.REVIEWED);
        review.setReviewedAt(LocalDateTime.now());
        review.setReviewedByUserId(callerId);
        review.setReviewNote(note.trim());
        SafeguardingReview saved = reviewRepository.save(review);
        inBasketService.removeAllItemsForReference(saved.getId(), SafeguardingReviewWorker.SAFEGUARDING_REFERENCE_TYPE);
        inBasketService.removeAllItemsForReference(saved.getId(), SafeguardingReviewWorker.SENIOR_REVIEW_REFERENCE_TYPE);
        return toDto(saved);
    }

    /**
     * The caller's acting hospital, when the caller is on its treating team or administers it. Anybody else gets a
     * plain 403 that says nothing about whether a review exists.
     */
    UUID requireReaderHospital() {
        UUID callerId = roleValidator.getCurrentUserId();
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (callerId == null || hospitalId == null || !isReader(callerId, hospitalId)) {
            throw new AccessDeniedException("Access denied");
        }
        return hospitalId;
    }

    boolean isReader(UUID userId, UUID hospitalId) {
        return isRecorder(userId, hospitalId)
            || roleValidator.isMidwife(userId, hospitalId)
            || roleValidator.isNurse(userId, hospitalId)
            || roleValidator.isHospitalAdmin(userId, hospitalId);
    }

    boolean isRecorder(UUID userId, UUID hospitalId) {
        return userId != null && (roleValidator.isDoctor(userId, hospitalId)
            || roleValidator.isPhysician(userId, hospitalId)
            || roleValidator.isSurgeon(userId, hospitalId));
    }

    private SafeguardingReviewDTO toDto(SafeguardingReview review) {
        return SafeguardingReviewDTO.builder()
            .id(review.getId())
            .patientId(review.getPatientId())
            // Tenant-scoped: the review's hospital is the caller's acting hospital, where she is registered.
            .patientName(patientRepository.findById(review.getPatientId())
                .map(Patient::getFullName).orElse(null))
            .triggerCode(review.getTriggerCode())
            .sourceType(review.getSourceType())
            .patientAgeYears(review.getPatientAgeYears())
            .ageThresholdYears(review.getAgeThresholdYears())
            .provisionalRule(true)
            .status(review.getStatus())
            .raisedAt(review.getRaisedAt())
            .raisedByName(nameOf(review.getRaisedByUserId()))
            .reviewedAt(review.getReviewedAt())
            .reviewedByName(nameOf(review.getReviewedByUserId()))
            .reviewNote(review.getReviewNote())
            .build();
    }

    private String nameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
            .map(SafeguardingReviewService::displayName)
            .orElse(null);
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
