package com.example.hms.service.transfusion;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.OPositiveDecisionBasis;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.OPositiveReleaseDecision;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.repository.OPositiveReleaseDecisionRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.OPositiveReleaseAuthority;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What an emergency O RhD positive release owes its readers (V380, rule set EMERGENCY_O_POSITIVE_AUTHORITY): the
 * biologist's review item, the physician's countersignature task, the follow-up task of the exposure, and the
 * escalation of a countersignature past its deadline.
 *
 * <p><b>Nothing here runs in the transaction of a release.</b> The release writes its decision row and commits;
 * the row is the obligation ({@code notices_filed_at} is null). {@link #deliver} then resolves the recipients
 * and files the items in a transaction of its own, which also marks the row: a recipient that cannot be read or
 * an item that cannot be filed rolls that transaction back, the row stays owed, and the sweep
 * ({@code OPositiveCountersignEscalationScheduler}) delivers it again. A release is never held or undone by a
 * notice, and a notice is never lost to a failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY)
public class OPositiveReleaseNotices {

    public static final String REVIEW_TITLE_KEY = "transfusion.oPositive.review.inBasketTitle";
    public static final String REVIEW_ROUTINE_BODY_KEY = "transfusion.oPositive.review.routine.inBasketBody";
    public static final String REVIEW_URGENT_BODY_KEY = "transfusion.oPositive.review.urgent.inBasketBody";
    public static final String REVIEW_IMMEDIATE_BODY_KEY = "transfusion.oPositive.review.immediate.inBasketBody";
    public static final String REVIEW_PENDING_BODY_KEY = "transfusion.oPositive.review.pending.inBasketBody";
    public static final String COUNTERSIGN_TITLE_KEY = "transfusion.oPositive.countersign.inBasketTitle";
    public static final String COUNTERSIGN_BODY_KEY = "transfusion.oPositive.countersign.inBasketBody";
    public static final String FOLLOW_UP_TITLE_KEY = "transfusion.oPositive.followUp.inBasketTitle";
    public static final String FOLLOW_UP_BODY_KEY = "transfusion.oPositive.followUp.inBasketBody";
    public static final String OVERDUE_TITLE_KEY = "transfusion.oPositive.overdue.inBasketTitle";
    public static final String OVERDUE_BODY_KEY = "transfusion.oPositive.overdue.inBasketBody";
    public static final String REVIEW_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_REVIEW";
    public static final String COUNTERSIGN_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_COUNTERSIGN";
    public static final String FOLLOW_UP_REFERENCE_TYPE = "TRANSFUSION_RHD_EXPOSURE_FOLLOW_UP";
    public static final String OVERDUE_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_COUNTERSIGN_OVERDUE";
    private static final String DECISION_ENTITY_TYPE = "O_POSITIVE_RELEASE_DECISION";

    private final OPositiveReleaseDecisionRepository decisionRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    // ── The notices of a release ────────────────────────────────────────

    /** The releases whose notices are still owed, the oldest first. */
    public List<UUID> pendingIds() {
        return decisionRepository.findPendingNoticeIds();
    }

    /**
     * Files the notices of one release and marks the row, in one transaction of its own: the review item of
     * every biologist; the countersignature task of the physician named; the follow-up task of the exposure for
     * the clinicians of the request. With no biologist at the hospital the review goes to the clinicians instead
     * of nowhere. What is already done (a review made from the queue meanwhile) is not asked again. When one of
     * the notices has nobody who can act on it, what can be filed is filed and the row stays owed, loudly.
     *
     * @return the number of items filed when the row is now settled; 0 when it was settled before, or stays owed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deliver(UUID decisionId) {
        OPositiveReleaseDecision decision = decisionRepository.lockById(decisionId)
            .filter(row -> row.getNoticesFiledAt() == null).orElse(null);
        if (decision == null) {
            return 0;
        }
        TransfusionRequest request = decision.getRelease().getRequest();
        BloodUnit unit = decision.getRelease().getBloodUnit();
        UUID hospitalId = decision.getHospital().getId();
        Patient patient = request.getPatient();
        String patientName = patient != null ? patient.getFullName() : null;
        UUID issuer = decision.getIssuedByUserId();
        Set<UUID> clinicians = new LinkedHashSet<>();
        if (decision.getDecidedByUserId() != null && !Objects.equals(decision.getDecidedByUserId(), issuer)) {
            clinicians.add(decision.getDecidedByUserId());
        }
        Staff requester = request.getRequestedBy();
        if (requester != null && requester.getUser() != null && !Objects.equals(requester.getUser().getId(), issuer)) {
            clinicians.add(requester.getUser().getId());
        }
        // Each obligation is filed for whoever can do it NOW; the row is marked only when every one of them had
        // somebody. Filing is idempotent (one item a reference and reader), so a row left owed is tried again.
        boolean complete = true;
        List<UUID> biologists = biologists(hospitalId);
        Collection<UUID> reviewers = biologists;
        boolean reviewerMissing = biologists.isEmpty();
        if (reviewerMissing) {
            // Only the biologist can review. With none at the hospital the clinicians are told, so somebody knows;
            // that copy settles nothing: the review stays owed and is delivered when a biologist holds the role.
            log.error("O positive release decision {} has no biologist to review it at hospital {}: its clinicians are "
                + "told, the review stays owed", decision.getId(), hospitalId);
            reviewers = whoCanAct(clinicians, hospitalId, OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES, Set.of(issuerOrNone(issuer)));
        }
        UUID encounterId = request.getEncounter() != null ? request.getEncounter().getId() : null;
        List<CreateInBasketItemRequest> items = new ArrayList<>();
        String decider = decision.getDecidedByUserId() == null ? ""
            : userRepository.findById(decision.getDecidedByUserId()).map(TransfusionMapper::userName).orElse("");
        Object[] unitArg = {unit.getUnitNumber()};

        CreateInBasketItemRequest review = notice(decision, patient, encounterId, REVIEW_REFERENCE_TYPE, priorityOf(decision));
        review.setTitleKey(REVIEW_TITLE_KEY);
        if (decision.getDecisionBasis() == OPositiveDecisionBasis.AUTHORITY_PENDING) {
            review.setBodyKey(REVIEW_PENDING_BODY_KEY);
        } else if (decision.getReviewPriority() == OPositiveReviewPriority.IMMEDIATE) {
            review.setBodyKey(REVIEW_IMMEDIATE_BODY_KEY);
        } else if (decision.getReviewPriority() == OPositiveReviewPriority.URGENT) {
            review.setBodyKey(REVIEW_URGENT_BODY_KEY);
        } else {
            review.setBodyKey(REVIEW_ROUTINE_BODY_KEY);
        }
        Object[] reviewArgs = {unit.getUnitNumber(), patientName, decider, decision.getReviewDeadlineHours()};
        // What is already done is not asked again: a review made from the queue before a delayed delivery.
        if (decision.reviewOwed()) {
            complete &= !reviewerMissing;
            reviewers.forEach(reviewer -> items.add(copyFor(reviewer, review, unitArg, reviewArgs)));
        }

        if (decision.countersignOwed()) {
            CreateInBasketItemRequest countersign = notice(decision, patient, encounterId, COUNTERSIGN_REFERENCE_TYPE,
                InBasketPriority.URGENT);
            countersign.setTitleKey(COUNTERSIGN_TITLE_KEY);
            countersign.setBodyKey(COUNTERSIGN_BODY_KEY);
            Object[] args = {unit.getUnitNumber(), patientName, decision.getReviewDeadlineHours()};
            // With nobody named, the clinician who asked for the blood is the one asked to sign; a signature
            // only a physician can give is asked of physicians, the hospital's when the request names none.
            Set<String> signers = decision.getRiskTier() == OPositiveRiskTier.KNOWN_ANTIBODY
                ? OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES : OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES;
            // Asked of those the countersignature will accept, and of nobody else: never the issuer, and for a
            // known antibody neither the biologist of the release nor its reviewer (two different people).
            Collection<UUID> askedToSign = whoCanAct(clinicians, hospitalId, signers, whoMayNotSign(decision));
            complete &= !askedToSign.isEmpty();
            askedToSign.forEach(physician -> items.add(copyFor(physician, countersign, unitArg, args)));
        }
        if (decision.followUpOwed() && bagCameBack(decision, unit)) {
            // The bag came back unused and no return wrote it: nobody was exposed, the follow-up is closed by
            // that fact and no task is filed for it.
            decision.setDisposition(com.example.hms.enums.OPositiveBagDisposition.RETURNED_UNUSED);
            decision.setDispositionAt(LocalDateTime.now(clock));
            decisionRepository.save(decision);
        }
        if (decision.followUpOwed()) {
            CreateInBasketItemRequest followUp = notice(decision, patient, encounterId, FOLLOW_UP_REFERENCE_TYPE,
                InBasketPriority.URGENT);
            followUp.setTitleKey(FOLLOW_UP_TITLE_KEY);
            followUp.setBodyKey(FOLLOW_UP_BODY_KEY);
            Object[] titleArgs = {patientName};
            Object[] args = {patientName, unit.getUnitNumber(), DisplayDates.date(decision.getAntibodyScreenDueFrom()),
                DisplayDates.date(decision.getAntibodyScreenDueBy())};
            Collection<UUID> followers = whoCanAct(clinicians, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES,
                Set.of(issuerOrNone(issuer)));
            complete &= !followers.isEmpty();
            followers.forEach(clinician -> items.add(copyFor(clinician, followUp, titleArgs, args)));
        }
        // In this transaction: an item that cannot be filed undoes everything here, and the sweep delivers again.
        items.forEach(inBasketService::createItem);
        if (!complete) {
            log.error("O positive release decision {} at hospital {}: a notice has nobody who can act on it; "
                + "what could be filed was filed, the rest stays owed", decision.getId(), hospitalId);
            return 0;
        }
        decision.setNoticesFiledAt(LocalDateTime.now(clock));
        decisionRepository.save(decision);
        return items.size();
    }

    /**
     * Those of the request's clinicians who hold, now and at this hospital, a role that lets them do the task and
     * are not excluded from it; when none does (a midwife's request, nobody named, somebody who left, the one
     * person the action refuses), everyone else at the hospital who holds such a role. Nobody the action would
     * refuse is ever asked, so an item filed always reaches somebody who can act on it.
     */
    private Collection<UUID> whoCanAct(Set<UUID> clinicians, UUID hospitalId, Set<String> roles, Set<UUID> excluded) {
        List<UUID> able = clinicians.stream().filter(clinician -> !excluded.contains(clinician))
            .filter(clinician -> assignmentRepository.existsActiveWitness(clinician, hospitalId, roles)).toList();
        if (!able.isEmpty()) {
            return able;
        }
        return assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, roles)
            .stream().map(User::getId).filter(id -> !excluded.contains(id)).distinct().toList();
    }

    /** Whom {@code countersign} refuses: the issuer; for a known antibody also the release's biologist and reviewer. */
    private static Set<UUID> whoMayNotSign(OPositiveReleaseDecision decision) {
        Set<UUID> excluded = new LinkedHashSet<>();
        excluded.add(issuerOrNone(decision.getIssuedByUserId()));
        if (decision.getRiskTier() == OPositiveRiskTier.KNOWN_ANTIBODY) {
            if (decision.getBiologistUserId() != null) {
                excluded.add(decision.getBiologistUserId());
            }
            if (decision.getReviewedByUserId() != null) {
                excluded.add(decision.getReviewedByUserId());
            }
        }
        return excluded;
    }

    /** A set cannot hold null: a release with no known issuer excludes nobody by that name. */
    private static UUID issuerOrNone(UUID issuer) {
        return issuer != null ? issuer : new UUID(0L, 0L);
    }

    /** The bag is neither hung on the request of this release nor still out on it: it came back. */
    private boolean bagCameBack(OPositiveReleaseDecision decision, BloodUnit unit) {
        if (decision.getDisposition() != null) {
            return decision.bagNotGiven();
        }
        UUID heldBy = unit != null && unit.getRequest() != null ? unit.getRequest().getId() : null;
        boolean stillOut = OPositiveReleaseAuthority.stillOut(unit != null ? unit.getStatus() : null, heldBy, decision.getRequestId());
        return !stillOut && decisionRepository.countAdministrations(decision.getBloodUnitId(), decision.getRequestId()) == 0;
    }

    /** A release with its authority still owed is urgent whatever its tier; a known antibody is critical. */
    private static InBasketPriority priorityOf(OPositiveReleaseDecision decision) {
        if (decision.getReviewPriority() == OPositiveReviewPriority.IMMEDIATE) {
            return InBasketPriority.CRITICAL;
        }
        boolean urgent = decision.getReviewPriority() == OPositiveReviewPriority.URGENT
            || decision.getDecisionBasis() == OPositiveDecisionBasis.AUTHORITY_PENDING;
        return urgent ? InBasketPriority.URGENT : InBasketPriority.NORMAL;
    }

    private List<UUID> biologists(UUID hospitalId) {
        return assignmentRepository
            .findLiveUsersAtHospitalWithRoleCodes(hospitalId, OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES)
            .stream().map(User::getId).distinct().toList();
    }

    /** One notice before its keys and its reader: a task about this decision, for this patient. */
    private static CreateInBasketItemRequest notice(OPositiveReleaseDecision decision, Patient patient, UUID encounterId,
                                                    String referenceType, InBasketPriority priority) {
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setHospitalId(decision.getHospital().getId());
        item.setItemType(InBasketItemType.TASK);
        item.setPriority(priority);
        item.setReferenceId(decision.getId());
        item.setReferenceType(referenceType);
        item.setEncounterId(encounterId);
        item.setPatientId(patient != null ? patient.getId() : decision.getPatientId());
        item.setPatientName(patient != null ? patient.getFullName() : null);
        return item;
    }

    /**
     * One reader's copy of a notice. {@code template} carries the title and body keys: the reader's copy is
     * rendered from them in their language, and the stored text is the staff-language copy.
     */
    private static CreateInBasketItemRequest copyFor(UUID recipient, CreateInBasketItemRequest template,
                                                     Object[] titleArgs, Object[] bodyArgs) {
        String titleKey = template.getTitleKey();
        String bodyKey = template.getBodyKey();
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setRecipientUserId(recipient);
        item.setHospitalId(template.getHospitalId());
        item.setItemType(template.getItemType());
        item.setPriority(template.getPriority());
        item.setReferenceId(template.getReferenceId());
        item.setReferenceType(template.getReferenceType());
        item.setEncounterId(template.getEncounterId());
        item.setPatientId(template.getPatientId());
        item.setPatientName(template.getPatientName());
        item.setTitleKey(titleKey);
        item.setTitleArgs(titleArgs);
        item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, titleKey, titleArgs));
        item.setBodyKey(bodyKey);
        item.setBodyArgs(bodyArgs);
        item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, bodyKey, bodyArgs));
        return item;
    }

    // ── The overdue countersignature ────────────────────────────────────

    /** The countersignatures past their deadline that nobody was told about yet, the oldest first. */
    public List<UUID> overdueCountersignIds() {
        return decisionRepository.findOverdueCountersignIds(LocalDateTime.now(clock));
    }

    /**
     * One countersignature past its deadline is escalated to the laboratory director, once. The alert is filed
     * in the transaction that marks the row as escalated: if it cannot be delivered the mark is not kept, and
     * the next sweep tries again. With no laboratory director the row is left to the next sweep, loudly: marking
     * it escalated would say somebody was told.
     *
     * @return the number of items filed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int escalateOverdueCountersign(UUID decisionId) {
        OPositiveReleaseDecision decision = decisionRepository.lockById(decisionId)
            .filter(row -> row.countersignOwed() && row.getCountersignEscalatedAt() == null).orElse(null);
        if (decision == null) {
            return 0;
        }
        UUID hospitalId = decision.getHospital().getId();
        List<UUID> directors = biologists(hospitalId);
        if (directors.isEmpty()) {
            log.error("Overdue countersignature of O positive release decision {} has no laboratory director to go to",
                decision.getId());
            return 0;
        }
        Patient patient = patientRepository.findByIdUnscoped(decision.getPatientId()).orElse(null);
        String unitNumber = decision.getRelease().getBloodUnit().getUnitNumber();
        Object[] titleArgs = {unitNumber};
        Object[] bodyArgs = {unitNumber, patient != null ? patient.getFullName() : null,
            decision.getReviewDeadlineHours()};
        CreateInBasketItemRequest overdue = notice(decision, patient, null, OVERDUE_REFERENCE_TYPE, InBasketPriority.URGENT);
        overdue.setTitleKey(OVERDUE_TITLE_KEY);
        overdue.setBodyKey(OVERDUE_BODY_KEY);
        decision.setCountersignEscalatedAt(LocalDateTime.now(clock));
        decisionRepository.save(decision);
        for (UUID director : directors) {
            // In this transaction, not after it: a failure here undoes the mark above.
            inBasketService.createItem(copyFor(director, overdue, titleArgs, bodyArgs));
        }
        UUID id = decision.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .hospitalId(hospitalId)
                    .eventType(AuditEventType.O_POSITIVE_COUNTERSIGN_OVERDUE)
                    .status(AuditStatus.SUCCESS)
                    .eventDescription(
                        "Emergency O positive release countersignature overdue, escalated to the laboratory director: decision "
                            + id)
                    .entityType(DECISION_ENTITY_TYPE)
                    .resourceId(String.valueOf(id))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit not written for the overdue countersignature of O positive release decision {}: {}", id,
                    ex.getClass().getSimpleName());
            }
        });
        return directors.size();
    }
}
