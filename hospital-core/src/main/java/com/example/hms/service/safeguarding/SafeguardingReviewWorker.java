package com.example.hms.service.safeguarding;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.model.User;
import com.example.hms.model.safeguarding.SafeguardingReview;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.SafeguardingReviewRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * r53, provisional rule (user decision 2026-11-14, pending approval): the transactional half of
 * {@link MinorLabourSafeguarding}. Runs after the admission committed, in its own transaction, so a failure here
 * never undoes or delays the admission.
 *
 * <p>Who is told, at the admission's own hospital only (never the caller's other hospitals, never a relative, never
 * the patient, never by SMS):
 * <ul>
 *   <li>the URGENT safeguarding task ({@link #SAFEGUARDING_REFERENCE_TYPE}): the clinicians who may act on it, the
 *       hospital's doctors, surgeons and midwives ({@link #SAFEGUARDING_ROLE_CODES});</li>
 *   <li>the senior obstetric review request ({@link #SENIOR_REVIEW_REFERENCE_TYPE}): its doctors and surgeons
 *       ({@link #SENIOR_REVIEW_ROLE_CODES}).</li>
 * </ul>
 * Hospital administrators read the open reviews on the safeguarding list; their only in-basket item stays the
 * dispute review (E9 #67, InBasketRecipientGuardTest). Both tasks clear when the review is recorded.
 * The wording is neutral: it states a review is required, never that abuse is suspected or confirmed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SafeguardingReviewWorker {

    public static final String SAFEGUARDING_REFERENCE_TYPE = "SAFEGUARDING_REVIEW";
    public static final String SENIOR_REVIEW_REFERENCE_TYPE = "SENIOR_OBSTETRIC_REVIEW";

    static final String TASK_TITLE_KEY = "safeguarding.minorLabour.task.title";
    static final String TASK_BODY_KEY = "safeguarding.minorLabour.task.body";
    static final String SENIOR_TITLE_KEY = "safeguarding.minorLabour.seniorReview.title";
    static final String SENIOR_BODY_KEY = "safeguarding.minorLabour.seniorReview.body";

    /** The clinicians who receive the safeguarding task. */
    static final Set<String> SAFEGUARDING_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_SURGEON, SecurityConstants.ROLE_MIDWIFE);

    /** Senior obstetric review: the hospital's physicians. */
    static final Set<String> SENIOR_REVIEW_ROLE_CODES = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_SURGEON);

    private final SafeguardingReviewRepository reviewRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;
    private final InBasketService inBasketService;

    /**
     * Opens the review, unless one is already open for her at this hospital (the admission and the labour episode
     * of one stay raise one review), and files the two tasks.
     *
     * @return the new review's id, or null when one was already open or the source is unknown
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID open(MinorLabourSafeguarding.LabourAdmission admission) {
        if (!MinorLabourSafeguarding.knownSource(admission.sourceType())) {
            log.error("[SAFEGUARDING] Unknown source type {}: review not opened", admission.sourceType());
            return null;
        }
        if (reviewRepository.existsByPatientIdAndHospitalIdAndTriggerCodeAndStatus(admission.patientId(),
                admission.hospitalId(), SafeguardingReview.TRIGGER_MINOR_LABOUR, SafeguardingReviewStatus.OPEN)) {
            return null;
        }
        SafeguardingReview review = reviewRepository.saveAndFlush(SafeguardingReview.builder()
            .hospitalId(admission.hospitalId())
            .patientId(admission.patientId())
            .triggerCode(SafeguardingReview.TRIGGER_MINOR_LABOUR)
            .sourceType(admission.sourceType())
            .sourceId(admission.sourceId())
            .patientAgeYears(admission.ageYears())
            .ageThresholdYears(admission.thresholdYears())
            .raisedAt(LocalDateTime.now())
            .raisedByUserId(admission.raisedByUserId())
            .status(SafeguardingReviewStatus.OPEN)
            .build());
        String patientName = patientRepository.findByIdUnscoped(admission.patientId())
            .map(com.example.hms.model.Patient::getFullName).orElse(null);
        Object[] args = {admission.ageYears(), admission.thresholdYears()};
        int filed = file(review, SAFEGUARDING_REFERENCE_TYPE, SAFEGUARDING_ROLE_CODES, TASK_TITLE_KEY, TASK_BODY_KEY,
            args, patientName);
        filed += file(review, SENIOR_REVIEW_REFERENCE_TYPE, SENIOR_REVIEW_ROLE_CODES, SENIOR_TITLE_KEY,
            SENIOR_BODY_KEY, args, patientName);
        if (filed == 0) {
            // The review stays open on the hospital's list all the same.
            log.error("[SAFEGUARDING] Review {} at hospital {}: no doctor, surgeon or midwife to task",
                review.getId(), admission.hospitalId());
        }
        return review.getId();
    }

    /** One URGENT task per reader. Each filing is guarded: one bad recipient never stops the others. */
    private int file(SafeguardingReview review, String referenceType, Set<String> roleCodes, String titleKey,
                     String bodyKey, Object[] bodyArgs, String patientName) {
        List<User> readers = assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(review.getHospitalId(),
            roleCodes);
        int filed = 0;
        for (User reader : readers) {
            CreateInBasketItemRequest item = new CreateInBasketItemRequest();
            item.setRecipientUserId(reader.getId());
            item.setHospitalId(review.getHospitalId());
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceId(review.getId());
            item.setReferenceType(referenceType);
            item.setPatientId(review.getPatientId());
            item.setPatientName(patientName);
            item.setTitleKey(titleKey);
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, titleKey));
            item.setBodyKey(bodyKey);
            item.setBodyArgs(bodyArgs);
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, bodyKey, bodyArgs));
            try {
                inBasketService.createItemInNewTransaction(item);
                filed++;
            } catch (RuntimeException ex) {
                log.warn("[SAFEGUARDING] Task for review {} not filed: {}", review.getId(),
                    ex.getClass().getSimpleName());
            }
        }
        return filed;
    }
}
