package com.example.hms.service.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.ONegativeStatus;
import com.example.hms.enums.OPositiveDecisionBasis;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.RhFactor;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.OPositiveReleaseDecision;
import com.example.hms.model.OPositiveReleaseProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO;
import com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO;
import com.example.hms.repository.OPositiveReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseProtocolRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.EmergencyReleasePolicy;
import com.example.hms.service.support.OPositiveReleaseAuthority;
import com.example.hms.service.support.OPositiveReleaseAuthority.Settings;
import com.example.hms.service.support.OPositiveReleaseAuthority.Subject;
import com.example.hms.service.support.OPositiveReleaseAuthority.Tier;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The ONE decision service for an emergency release of O RhD positive red cells (owner decision 2026-11-25,
 * rule set EMERGENCY_O_POSITIVE_AUTHORITY, « provisoire »): it applies {@link OPositiveReleaseAuthority} to a
 * request, says whose name the release must carry, and keeps the decision, its countersignature, the review by
 * the biologist and the follow-up of the exposure. Every way a red-cell bag leaves the laboratory uncrossmatched
 * or on an emergency decision passes {@link #authorise} and {@link #record}
 * ({@code EmergencyOPositiveAuthorityGuardTest}).
 *
 * <p><b>It never holds blood back for want of a person.</b> The low-risk tier leaves by the plain issue. Every
 * other tier leaves by the documented release, which the issuer can always complete alone: with the name of
 * the physician who gave the order, or stating that none can be reached. The bag then goes out with the
 * authority recorded as owed, the laboratory director is alerted at once and the countersignature stays due.
 * What it does refuse is a record that would be false: the issuer named as the physician.
 *
 * <p>The release paths call it inside their own transaction. The countersignature, the review, the follow-up
 * and the sweep are called from a transactional method of {@code TransfusionServiceImpl} or the scheduler.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.EMERGENCY_O_POSITIVE_AUTHORITY)
public class OPositiveReleaseAuthorityService {

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
    static final String REVIEW_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_REVIEW";
    static final String COUNTERSIGN_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_COUNTERSIGN";
    static final String FOLLOW_UP_REFERENCE_TYPE = "TRANSFUSION_RHD_EXPOSURE_FOLLOW_UP";
    static final String OVERDUE_REFERENCE_TYPE = "TRANSFUSION_O_POSITIVE_COUNTERSIGN_OVERDUE";
    private static final String DECISION_ENTITY_TYPE = "O_POSITIVE_RELEASE_DECISION";
    private static final String PROTOCOL_ENTITY_TYPE = "HOSPITAL_O_POSITIVE_PROTOCOL";
    private static final String FIELD_AUTHORISER = "authoriserUserId";
    private static final String FIELD_BIOLOGIST = "biologistUserId";
    private static final String MSG_DECISION_NOT_FOUND = "transfusion.oPositive.decision.notFound";
    private static final int MAX_NOTE_LENGTH = 2000;
    /** The most releases one read of the queue returns. */
    private static final int MAX_LISTED = 200;

    /** What a reader acting at one hospital is told when the flag rests on the record of another. */
    public static final String REACH_DESCRIPTION =
        "Cross-hospital RhD positive exposure flag read (transfusion safety, shown whatever the sharing choice)";

    private final OPositiveReleaseProtocolRepository protocolRepository;
    private final OPositiveReleaseDecisionRepository decisionRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    /** The account of cross-hospital reads. Optional for the sliced test contexts only. */
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReachRecorder(com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder) {
        this.reachRecorder = reachRecorder;
    }

    // ── The table applied to one request ────────────────────────────────

    /** What the table says for one patient at one hospital, now. */
    public record Assessment(Tier tier, Settings settings, Subject subject) {

        public OPositiveRiskTier riskTier() {
            return tier.tier();
        }

        /** The bag may leave by the plain issue: nothing is asked of the issuer. */
        public boolean plainIssueAllowed() {
            return OPositiveReleaseAuthority.plainIssueAllowed(tier, subject.typedRh());
        }

        /** O negative shown in stock is stated unobtainable before O positive goes out to this patient. */
        public boolean oNegativeFirst() {
            return OPositiveReleaseAuthority.oNegativeFirst(subject, settings);
        }
    }

    /**
     * What one release was allowed on. Built by {@link #authorise} only: holding one is the proof that the
     * decision service saw the release.
     */
    public static final class Authorisation {
        private final Assessment assessment;
        private final OPositiveDecisionBasis basis;
        private final UUID decidedBy;
        private final UUID biologist;
        private final boolean verbalOrder;
        private final UUID issuer;

        private Authorisation(Assessment assessment, OPositiveDecisionBasis basis, UUID decidedBy, UUID biologist,
                              boolean verbalOrder, UUID issuer) {
            this.assessment = assessment;
            this.basis = basis;
            this.decidedBy = decidedBy;
            this.biologist = biologist;
            this.verbalOrder = verbalOrder;
            this.issuer = issuer;
        }

        /** False for a bag that is not O RhD positive red cells: this rule has nothing to say about it. */
        public boolean applies() {
            return assessment != null;
        }

        public OPositiveDecisionBasis basis() {
            return basis;
        }

        /** Who decided, or null while the authority is owed. */
        public UUID decidedBy() {
            return decidedBy;
        }

        /**
         * The name the release row carries as answering for it: who decided, and while the authority is owed
         * the person who released the bag, who is the one who took that step.
         */
        public UUID answerable() {
            return decidedBy != null ? decidedBy : issuer;
        }
    }

    private static final Authorisation NOT_O_POSITIVE = new Authorisation(null, null, null, null, false, null);

    public Settings settings(UUID hospitalId) {
        return protocolRepository.findByHospital_Id(hospitalId)
            .map(protocol -> new Settings(protocol.isActive(), protocol.getFemaleAgeCutoff(),
                protocol.isBoysLowRisk(), protocol.getReviewDeadlineHours()))
            .orElse(Settings.NO_PROTOCOL);
    }

    /**
     * @param typedRh       the RhD the laboratory has on record for the patient here; null when untyped
     * @param antibodyKnown a positive antibody screen or an identified antibody is on record, at any hospital
     */
    public Assessment assess(TransfusionRequest request, RhFactor typedRh, boolean antibodyKnown) {
        Patient patient = request.getPatient();
        LocalDate today = LocalDate.now(clock);
        Subject subject = new Subject(
            OPositiveReleaseAuthority.sexOf(patient != null ? patient.getGender() : null),
            OPositiveReleaseAuthority.ageYears(patient != null ? patient.getDateOfBirth() : null, today),
            typedRh, antibodyKnown);
        Settings settings = settings(request.getHospital().getId());
        return new Assessment(OPositiveReleaseAuthority.tierOf(subject, settings), settings, subject);
    }

    /**
     * Whose authority this release goes out on. Called by every release path before the bag is put out.
     *
     * <p>A plain issue ({@code input} null) passes for the low-risk tier and is otherwise sent to the documented
     * release. A documented release is refused only when what it names is wrong (the issuer as the physician,
     * somebody who holds no such role here) and never because nobody can be named: stating that no physician
     * can be reached releases the bag with the authority recorded as owed.
     *
     * @param input the documented release as submitted, or null for a plain issue
     */
    public Authorisation authorise(TransfusionRequest request, BloodUnit unit, EmergencyReleaseType type,
                                   EmergencyReleaseRequestDTO input, Assessment assessment) {
        if (!AboGroup.isEmergencyOPositive(unit.getAboGroup(), unit.getRhFactor(), unit.getProductType())) {
            return NOT_O_POSITIVE;
        }
        UUID issuer = roleValidator.getCurrentUserId();
        UUID hospitalId = request.getHospital().getId();
        if (input == null || type == EmergencyReleaseType.UNCROSSMATCHED) {
            if (!assessment.plainIssueAllowed()) {
                throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.decisionRequired",
                    unit.getUnitNumber());
            }
            return new Authorisation(assessment, OPositiveDecisionBasis.PROTOCOL, issuer, null, false, issuer);
        }
        UUID named = input.getAuthoriserUserId();
        boolean verbal = !Boolean.FALSE.equals(input.getVerbalOrder());
        return switch (assessment.riskTier()) {
            case LOW_RISK -> {
                // Under the protocol the issuer decides alone: with nobody named, the decision is the issuer's.
                UUID decider = named != null ? named : issuer;
                requireNamed(decider, "transfusion.emergency.authoriserRequired");
                requireRole(decider, hospitalId, EmergencyReleasePolicy.O_POSITIVE_DECIDER_ROLE_CODES, FIELD_AUTHORISER,
                    "transfusion.emergency.authoriserInvalid");
                yield new Authorisation(assessment, OPositiveDecisionBasis.PROTOCOL, decider, null, false, issuer);
            }
            case HIGH_RISK -> {
                UUID physician = physician(input, issuer, hospitalId, OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES);
                yield physician == null
                    ? new Authorisation(assessment, OPositiveDecisionBasis.AUTHORITY_PENDING, null, null, false, issuer)
                    : new Authorisation(assessment, OPositiveDecisionBasis.PHYSICIAN_ORDER, physician, null, verbal, issuer);
            }
            case KNOWN_ANTIBODY -> {
                // An incompatible release is authorised by a doctor or the laboratory director: either is kept.
                Set<String> roles = type == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE
                    ? OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES
                    : OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES;
                UUID physician = physician(input, issuer, hospitalId, roles);
                UUID biologist = biologist(input, issuer, physician, hospitalId);
                boolean together = physician != null && biologist != null;
                yield new Authorisation(assessment,
                    together ? OPositiveDecisionBasis.PHYSICIAN_AND_BIOLOGIST : OPositiveDecisionBasis.AUTHORITY_PENDING,
                    physician, biologist, physician != null && verbal, issuer);
            }
        };
    }

    private static void requireNamed(UUID named, String messageKey) {
        if (named == null) {
            throw new FieldBusinessException(FIELD_AUTHORISER, messageKey);
        }
    }

    private void requireRole(UUID userId, UUID hospitalId, Set<String> roles, String field, String messageKey) {
        if (!assignmentRepository.existsActiveWitness(userId, hospitalId, roles)) {
            throw new FieldBusinessException(field, messageKey);
        }
    }

    /**
     * The physician the release names, who is never the issuer; or null when the issuer states that none can be
     * reached. Naming nobody without that statement, or naming oneself, is asked again: the answer is one field.
     */
    private UUID physician(EmergencyReleaseRequestDTO input, UUID issuer, UUID hospitalId, Set<String> roles) {
        UUID named = input.getAuthoriserUserId();
        boolean unreachable = Boolean.TRUE.equals(input.getPhysicianUnreachable());
        if (unreachable && (named == null || Objects.equals(named, issuer))) {
            return null;
        }
        requireNamed(named, "transfusion.emergency.oPositive.physicianRequired");
        if (Objects.equals(named, issuer)) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.oPositive.physicianNotIssuer");
        }
        requireRole(named, hospitalId, roles, FIELD_AUTHORISER, "transfusion.emergency.oPositive.physicianInvalid");
        return named;
    }

    /**
     * The biologist deciding with the physician: the one named, or the issuer when the issuer is the biologist.
     * Null when neither: the release still goes out, and every biologist is alerted at once.
     */
    private UUID biologist(EmergencyReleaseRequestDTO input, UUID issuer, UUID physician, UUID hospitalId) {
        UUID named = input.getBiologistUserId();
        if (named != null) {
            if (Objects.equals(named, physician)) {
                throw new FieldBusinessException(FIELD_BIOLOGIST, "transfusion.emergency.oPositive.biologistNotPhysician");
            }
            requireRole(named, hospitalId, OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES, FIELD_BIOLOGIST,
                "transfusion.emergency.oPositive.biologistInvalid");
            return named;
        }
        boolean issuerIsBiologist = issuer != null && !Objects.equals(issuer, physician)
            && assignmentRepository.existsActiveWitness(issuer, hospitalId, OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES);
        return issuerIsBiologist ? issuer : null;
    }

    /**
     * Keeps the decision beside its release row and files what follows it. A bag that is not O positive red
     * cells writes nothing. The notices are composed here, in the transaction, and filed after the commit: a
     * notice that cannot be filed never undoes the release.
     *
     * @return the decision row, or null when the rule does not apply to the bag
     */
    public OPositiveReleaseDecision record(EmergencyBloodRelease release, Authorisation authorisation,
                                           TransfusionRequest request, BloodUnit unit, ONegativeStatus negativeStatus) {
        if (!authorisation.applies()) {
            return null;
        }
        Assessment assessment = authorisation.assessment;
        Settings settings = assessment.settings();
        Subject subject = assessment.subject();
        OPositiveRiskTier tier = assessment.riskTier();
        LocalDateTime issuedAt = release.getIssuedAt();
        OPositiveReviewPriority priority = OPositiveReleaseAuthority.reviewPriority(tier);
        boolean exposure = OPositiveReleaseAuthority.exposureFollowUp(tier, subject.typedRh());
        LocalDate issuedOn = issuedAt.toLocalDate();
        OPositiveReleaseDecision saved = decisionRepository.save(OPositiveReleaseDecision.builder()
            .release(release)
            .requestId(request.getId())
            .bloodUnitId(unit.getId())
            .patientId(request.getPatient().getId())
            .hospital(request.getHospital())
            .riskTier(tier)
            .tierReason(assessment.tier().reason())
            .protocolActive(settings.protocolActive())
            .femaleAgeCutoff(settings.femaleAgeCutoff())
            .boysLowRisk(settings.boysLowRisk())
            .reviewDeadlineHours(settings.reviewDeadlineHours())
            .patientSex(subject.sex().name())
            .patientAgeYears(subject.ageYears())
            .patientRh(subject.typedRh() != null ? subject.typedRh().name() : null)
            .negativeStatus(negativeStatus)
            .decisionBasis(authorisation.basis)
            .issuedByUserId(authorisation.issuer)
            .decidedByUserId(authorisation.decidedBy)
            .biologistUserId(authorisation.biologist)
            .verbalOrder(authorisation.verbalOrder)
            .issuedAt(issuedAt)
            .countersignDueAt(tier == OPositiveRiskTier.LOW_RISK ? null : issuedAt.plusHours(settings.reviewDeadlineHours()))
            .reviewPriority(priority)
            .reviewDueAt(reviewDueAt(priority, issuedAt, settings))
            .rhdExposure(exposure)
            .followUpRequired(exposure)
            .antibodyScreenDueFrom(exposure ? issuedOn.plusMonths(OPositiveReleaseAuthority.ANTIBODY_SCREEN_FROM_MONTHS) : null)
            .antibodyScreenDueBy(exposure ? issuedOn.plusMonths(OPositiveReleaseAuthority.ANTIBODY_SCREEN_BY_MONTHS) : null)
            .build());
        notifySafely(() -> fileNotices(saved, request, unit), saved.getId());
        auditAfterCommit(AuditEventType.O_POSITIVE_RELEASE_DECIDED, saved, authorisation.issuer,
            "Emergency O positive release decision recorded for blood unit " + saved.getBloodUnitId()
                + " against transfusion request " + saved.getRequestId());
        return saved;
    }

    private static LocalDateTime reviewDueAt(OPositiveReviewPriority priority, LocalDateTime issuedAt, Settings settings) {
        return switch (priority) {
            case ROUTINE -> issuedAt.plusDays(OPositiveReleaseAuthority.ROUTINE_REVIEW_DAYS);
            case URGENT -> issuedAt.plusHours(settings.reviewDeadlineHours());
            case IMMEDIATE -> issuedAt;
        };
    }

    // ── What follows a release ──────────────────────────────────────────

    /**
     * The review item of every biologist; the countersignature task of the physician named; the follow-up task
     * of the exposure for the clinicians of the request. With no biologist at the hospital the review goes to
     * the clinicians instead of nowhere.
     */
    private void fileNotices(OPositiveReleaseDecision decision, TransfusionRequest request, BloodUnit unit) {
        UUID hospitalId = request.getHospital().getId();
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
        List<UUID> biologists = biologists(hospitalId);
        Collection<UUID> reviewers = biologists;
        if (biologists.isEmpty()) {
            log.warn("O positive release decision {} has no biologist to review it at hospital {}: its clinicians are told",
                decision.getId(), hospitalId);
            reviewers = clinicians;
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
        reviewers.forEach(reviewer -> items.add(copyFor(reviewer, review, unitArg, reviewArgs)));

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
            whoCanAct(clinicians, hospitalId, signers, issuer)
                .forEach(physician -> items.add(copyFor(physician, countersign, unitArg, args)));
        }
        if (decision.isFollowUpRequired()) {
            CreateInBasketItemRequest followUp = notice(decision, patient, encounterId, FOLLOW_UP_REFERENCE_TYPE,
                InBasketPriority.URGENT);
            followUp.setTitleKey(FOLLOW_UP_TITLE_KEY);
            followUp.setBodyKey(FOLLOW_UP_BODY_KEY);
            Object[] titleArgs = {patientName};
            Object[] args = {patientName, unit.getUnitNumber(), DisplayDates.date(decision.getAntibodyScreenDueFrom()),
                DisplayDates.date(decision.getAntibodyScreenDueBy())};
            whoCanAct(clinicians, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES, issuer)
                .forEach(clinician -> items.add(copyFor(clinician, followUp, titleArgs, args)));
        }
        fileAfterCommit(items, decision.getId());
    }

    /**
     * Those of the request's clinicians who hold a role that lets them do the task; when none does (a midwife's
     * request, nobody named), every physician of the hospital, so the task is never filed for nobody. The issuer
     * is never asked to sign their own release.
     */
    private Collection<UUID> whoCanAct(Set<UUID> clinicians, UUID hospitalId, Set<String> roles, UUID issuer) {
        List<UUID> able = clinicians.stream()
            .filter(clinician -> assignmentRepository.existsActiveWitness(clinician, hospitalId, roles)).toList();
        if (!able.isEmpty()) {
            return able;
        }
        return assignmentRepository
            .findLiveUsersAtHospitalWithRoleCodes(hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES)
            .stream().map(User::getId).filter(id -> !Objects.equals(id, issuer)).distinct().toList();
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

    private void fileAfterCommit(List<CreateInBasketItemRequest> items, UUID decisionId) {
        TransactionCallbacks.afterCommit(() -> {
            for (CreateInBasketItemRequest item : items) {
                try {
                    inBasketService.createItemInNewTransaction(item);
                } catch (RuntimeException ex) {
                    log.warn("In-basket item not filed for {} {}: {}", item.getReferenceType(), decisionId,
                        ex.getClass().getSimpleName());
                }
            }
        });
    }

    /** A notice that will not compose must not undo the release it reports. */
    private static void notifySafely(Runnable compose, UUID referenceId) {
        try {
            compose.run();
        } catch (RuntimeException ex) {
            log.error("Notification not composed for O positive release decision {}: {}", referenceId,
                ex.getClass().getSimpleName());
        }
    }

    private void auditAfterCommit(AuditEventType type, OPositiveReleaseDecision decision, UUID actorUserId, String description) {
        UUID hospitalId = decision.getHospital().getId();
        UUID decisionId = decision.getId();
        // Codes only, no name and no narrative: the tier, the basis and the review of this release.
        Map<String, String> details = Map.of(
            "riskTier", decision.getRiskTier().name(),
            "tierReason", decision.getTierReason().name(),
            "decisionBasis", decision.getDecisionBasis().name(),
            "reviewPriority", decision.getReviewPriority().name(),
            "verbalOrder", String.valueOf(decision.isVerbalOrder()));
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(actorUserId)
                    .hospitalId(hospitalId)
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .eventDescription(description)
                    .entityType(DECISION_ENTITY_TYPE)
                    .resourceId(String.valueOf(decisionId))
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit {} not written for O positive release decision {}: {}", type, decisionId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    // ── Countersignature, review, follow-up ─────────────────────────────

    /**
     * The physician signs the order the issuer recorded for them. Any physician of the hospital may sign (the
     * one named, or another answering for the service), a laboratory director too outside the antibody tier;
     * never the person who issued the bag.
     */
    public OPositiveReleaseDecisionDTO countersign(UUID decisionId, UUID hospitalId) {
        OPositiveReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (!decision.countersignOwed()) {
            throw new FieldBusinessException("decisionId", "transfusion.oPositive.countersign.notOwed");
        }
        if (caller == null || Objects.equals(caller, decision.getIssuedByUserId())) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.countersign.notIssuer"));
        }
        Set<String> roles = decision.getRiskTier() == OPositiveRiskTier.KNOWN_ANTIBODY
            ? OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES
            : OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES;
        if (!assignmentRepository.existsActiveWitness(caller, hospitalId, roles)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.countersign.physicianOnly"));
        }
        decision.setCountersignedByUserId(caller);
        decision.setCountersignedAt(LocalDateTime.now(clock));
        decisionRepository.save(decision);
        resolveItems(decision.getId(), COUNTERSIGN_REFERENCE_TYPE, OVERDUE_REFERENCE_TYPE);
        auditAfterCommit(AuditEventType.O_POSITIVE_RELEASE_COUNTERSIGNED, decision, caller,
            "Emergency O positive release countersigned by a physician: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /** The review of the biologist: the laboratory director of this hospital, once. */
    public OPositiveReleaseDecisionDTO review(UUID decisionId, UUID hospitalId, String note) {
        OPositiveReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId,
                OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.review.biologistOnly"));
        }
        if (!decision.reviewOwed()) {
            throw new FieldBusinessException("decisionId", "transfusion.oPositive.review.alreadyReviewed");
        }
        decision.setReviewedByUserId(caller);
        decision.setReviewedAt(LocalDateTime.now(clock));
        decision.setReviewNote(bounded(note, "note"));
        decisionRepository.save(decision);
        resolveItems(decision.getId(), REVIEW_REFERENCE_TYPE);
        auditAfterCommit(AuditEventType.O_POSITIVE_RELEASE_REVIEWED, decision, caller,
            "Emergency O positive release reviewed by the biologist: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /**
     * The clinician records the anti-D or exchange decision and that the patient was told; each is written once.
     * The task stays open until both are on record.
     */
    public OPositiveReleaseDecisionDTO recordFollowUp(UUID decisionId, UUID hospitalId, OPositiveFollowUpRequestDTO request) {
        OPositiveReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (!decision.isFollowUpRequired()) {
            throw new FieldBusinessException("decisionId", "transfusion.oPositive.followUp.notRequired");
        }
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId,
                OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.followUp.physicianOnly"));
        }
        boolean informs = Boolean.TRUE.equals(request.getPatientInformed()) && decision.getPatientInformedAt() == null;
        boolean decides = request.getAntiDDecision() != null;
        if (decides && decision.getAntiDDecision() != null) {
            throw new FieldBusinessException("antiDDecision", "transfusion.oPositive.followUp.alreadyDecided");
        }
        if (!informs && !decides) {
            throw new FieldBusinessException("antiDDecision", "transfusion.oPositive.followUp.nothingRecorded");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (decides) {
            decision.setAntiDDecision(request.getAntiDDecision());
            decision.setAntiDDecidedByUserId(caller);
            decision.setAntiDDecidedAt(now);
        }
        if (informs) {
            decision.setPatientInformedByUserId(caller);
            decision.setPatientInformedAt(now);
        }
        String note = bounded(request.getNote(), "note");
        if (note != null) {
            decision.setFollowUpNote(decision.getFollowUpNote() == null ? note : decision.getFollowUpNote() + "\n" + note);
        }
        decisionRepository.save(decision);
        if (!decision.followUpOwed()) {
            resolveItems(decision.getId(), FOLLOW_UP_REFERENCE_TYPE);
        }
        auditAfterCommit(AuditEventType.O_POSITIVE_FOLLOW_UP_RECORDED, decision, caller,
            "RhD positive exposure follow-up recorded: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /**
     * An antibody screen was recorded for the patient: the follow-ups whose window has opened have their screen.
     * A screen before the window (the antibody takes about three months to form) leaves them waiting.
     */
    public void noteAntibodyScreen(UUID patientId, LocalDateTime performedAt) {
        if (patientId == null || performedAt == null) {
            return;
        }
        for (OPositiveReleaseDecision decision
                : decisionRepository.findByPatientIdAndFollowUpRequiredTrueAndAntibodyScreenDoneAtIsNull(patientId)) {
            if (decision.getAntibodyScreenDueFrom() != null
                    && !performedAt.toLocalDate().isBefore(decision.getAntibodyScreenDueFrom())) {
                decision.setAntibodyScreenDoneAt(performedAt);
                decisionRepository.save(decision);
            }
        }
    }

    private static String bounded(String text, String field) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new FieldBusinessException(field, "transfusion.oPositive.noteTooLong", MAX_NOTE_LENGTH);
        }
        return trimmed;
    }

    /**
     * The tasks of a step that is done leave the in-baskets of those who did not do it, in the transaction of
     * the step. An acknowledged item stays, as the record of who read it.
     */
    private void resolveItems(UUID decisionId, String... referenceTypes) {
        for (String referenceType : referenceTypes) {
            inBasketService.removeOpenItemsForReference(decisionId, referenceType);
        }
    }

    private OPositiveReleaseDecision lockScoped(UUID decisionId, UUID hospitalId) {
        return decisionRepository.lockById(decisionId)
            .filter(decision -> Objects.equals(decision.getHospital().getId(), hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DECISION_NOT_FOUND, decisionId));
    }

    // ── The overdue countersignature ────────────────────────────────────

    /**
     * A countersignature past its deadline is escalated to the laboratory director, once. With no laboratory
     * director the row is left to the next sweep, loudly: marking it escalated would say somebody was told.
     *
     * @return the number of items filed
     */
    @org.springframework.transaction.annotation.Transactional
    public int escalateOverdueCountersigns() {
        LocalDateTime now = LocalDateTime.now(clock);
        int filed = 0;
        for (UUID id : decisionRepository.findOverdueCountersignIds(now)) {
            OPositiveReleaseDecision decision = decisionRepository.lockById(id)
                .filter(row -> row.countersignOwed() && row.getCountersignEscalatedAt() == null).orElse(null);
            if (decision == null) {
                continue;
            }
            UUID hospitalId = decision.getHospital().getId();
            List<UUID> directors = biologists(hospitalId);
            if (directors.isEmpty()) {
                log.error("Overdue countersignature of O positive release decision {} has no laboratory director to go to",
                    decision.getId());
                continue;
            }
            Patient patient = patientRepository.findByIdUnscoped(decision.getPatientId()).orElse(null);
            String unitNumber = decision.getRelease().getBloodUnit().getUnitNumber();
            Object[] titleArgs = {unitNumber};
            Object[] bodyArgs = {unitNumber, patient != null ? patient.getFullName() : null,
                decision.getReviewDeadlineHours()};
            CreateInBasketItemRequest overdue = notice(decision, patient, null, OVERDUE_REFERENCE_TYPE, InBasketPriority.URGENT);
            overdue.setTitleKey(OVERDUE_TITLE_KEY);
            overdue.setBodyKey(OVERDUE_BODY_KEY);
            List<CreateInBasketItemRequest> items = new ArrayList<>();
            directors.forEach(director -> items.add(copyFor(director, overdue, titleArgs, bodyArgs)));
            decision.setCountersignEscalatedAt(now);
            decisionRepository.save(decision);
            fileAfterCommit(items, decision.getId());
            auditAfterCommit(AuditEventType.O_POSITIVE_COUNTERSIGN_OVERDUE, decision, null,
                "Emergency O positive release countersignature overdue, escalated to the laboratory director: decision "
                    + decision.getId());
            filed += items.size();
        }
        return filed;
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** The releases of one hospital for its review queue, the newest first; only what is still owed when asked. */
    public List<OPositiveReleaseDecisionDTO> list(UUID hospitalId, boolean openOnly) {
        List<OPositiveReleaseDecision> rows = decisionRepository.findByHospital_IdOrderByIssuedAtDesc(hospitalId);
        if (openOnly) {
            rows = rows.stream().filter(row -> row.reviewOwed() || row.countersignOwed() || row.followUpOwed()
                || (row.isFollowUpRequired() && row.getAntibodyScreenDoneAt() == null)).toList();
        }
        // The newest first; an old hospital's whole history is not sent to a screen.
        return toDto(rows.stream().limit(MAX_LISTED).toList());
    }

    /** The decisions of these release rows, by release id, for the request detail. */
    public Map<UUID, OPositiveReleaseDecisionDTO> byRelease(Collection<UUID> releaseIds) {
        Map<UUID, OPositiveReleaseDecisionDTO> byRelease = new LinkedHashMap<>();
        if (releaseIds == null || releaseIds.isEmpty()) {
            return byRelease;
        }
        for (OPositiveReleaseDecisionDTO dto : toDto(decisionRepository.findByRelease_IdIn(releaseIds))) {
            byRelease.put(dto.getReleaseId(), dto);
        }
        return byRelease;
    }

    private List<OPositiveReleaseDecisionDTO> toDto(List<OPositiveReleaseDecision> rows) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (OPositiveReleaseDecision row : rows) {
            for (UUID id : new UUID[] {row.getIssuedByUserId(), row.getDecidedByUserId(), row.getBiologistUserId(),
                row.getCountersignedByUserId(), row.getReviewedByUserId(), row.getAntiDDecidedByUserId(),
                row.getPatientInformedByUserId()}) {
                if (id != null) {
                    userIds.add(id);
                }
            }
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userRepository.findAllById(userIds)) {
                names.put(user.getId(), TransfusionMapper.userName(user));
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<OPositiveReleaseDecisionDTO> dtos = new ArrayList<>();
        for (OPositiveReleaseDecision row : rows) {
            EmergencyBloodRelease release = row.getRelease();
            BloodUnit unit = release != null ? release.getBloodUnit() : null;
            Patient patient = release != null && release.getRequest() != null ? release.getRequest().getPatient() : null;
            dtos.add(OPositiveReleaseDecisionDTO.builder()
                .id(row.getId())
                .releaseId(release != null ? release.getId() : null)
                .requestId(row.getRequestId())
                .bloodUnitId(row.getBloodUnitId())
                .unitNumber(unit != null ? unit.getUnitNumber() : null)
                .patientId(row.getPatientId())
                .patientName(patient != null ? patient.getFullName() : null)
                .riskTier(row.getRiskTier())
                .tierReason(row.getTierReason())
                .protocolActive(row.isProtocolActive())
                .femaleAgeCutoff(row.getFemaleAgeCutoff())
                .boysLowRisk(row.isBoysLowRisk())
                .reviewDeadlineHours(row.getReviewDeadlineHours())
                .patientSex(row.getPatientSex())
                .patientAgeYears(row.getPatientAgeYears())
                .negativeStatus(row.getNegativeStatus())
                .decisionBasis(row.getDecisionBasis())
                .issuedByName(names.get(row.getIssuedByUserId()))
                .decidedByName(names.get(row.getDecidedByUserId()))
                .biologistName(names.get(row.getBiologistUserId()))
                .verbalOrder(row.isVerbalOrder())
                .issuedAt(row.getIssuedAt())
                .countersignDueAt(row.getCountersignDueAt())
                .countersignedByName(names.get(row.getCountersignedByUserId()))
                .countersignedAt(row.getCountersignedAt())
                .countersignOverdue(row.countersignOwed() && row.getCountersignDueAt().isBefore(now))
                .countersignEscalatedAt(row.getCountersignEscalatedAt())
                .reviewPriority(row.getReviewPriority())
                .reviewDueAt(row.getReviewDueAt())
                .reviewedByName(names.get(row.getReviewedByUserId()))
                .reviewedAt(row.getReviewedAt())
                .reviewNote(row.getReviewNote())
                .rhdExposure(row.isRhdExposure())
                .followUpRequired(row.isFollowUpRequired())
                .antibodyScreenDueFrom(row.getAntibodyScreenDueFrom())
                .antibodyScreenDueBy(row.getAntibodyScreenDueBy())
                .antibodyScreenDoneAt(row.getAntibodyScreenDoneAt())
                .antiDDecision(row.getAntiDDecision())
                .antiDDecidedByName(names.get(row.getAntiDDecidedByUserId()))
                .antiDDecidedAt(row.getAntiDDecidedAt())
                .patientInformedByName(names.get(row.getPatientInformedByUserId()))
                .patientInformedAt(row.getPatientInformedAt())
                .followUpNote(row.getFollowUpNote())
                .build());
        }
        return dtos;
    }

    /** What the release form is told for one request, so it asks for what the server will require. */
    public EmergencyReleaseOptionsDTO.OPositiveRule ruleFor(Assessment assessment, UUID hospitalId) {
        UUID caller = roleValidator.getCurrentUserId();
        OPositiveRiskTier tier = assessment.riskTier();
        Set<String> physicianRoles = tier == OPositiveRiskTier.KNOWN_ANTIBODY
            ? OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES
            : OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES;
        boolean named = tier != OPositiveRiskTier.LOW_RISK;
        return EmergencyReleaseOptionsDTO.OPositiveRule.builder()
            .riskTier(tier)
            .tierReason(assessment.tier().reason())
            .protocolActive(assessment.settings().protocolActive())
            .femaleAgeCutoff(assessment.settings().femaleAgeCutoff())
            .boysLowRisk(assessment.settings().boysLowRisk())
            .reviewDeadlineHours(assessment.settings().reviewDeadlineHours())
            .issuerMayDecide(!named)
            .plainIssueAllowed(assessment.plainIssueAllowed())
            .physicians(named ? candidates(hospitalId, physicianRoles, caller) : List.of())
            .biologists(tier == OPositiveRiskTier.KNOWN_ANTIBODY
                ? candidates(hospitalId, OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES, null) : List.of())
            .build();
    }

    private List<StockWitnessCandidateDTO> candidates(UUID hospitalId, Set<String> roles, UUID excluded) {
        Map<UUID, StockWitnessCandidateDTO> byUser = new LinkedHashMap<>();
        for (User user : assignmentRepository.findLiveUsersAtHospitalWithRoleCodes(hospitalId, roles)) {
            if (!user.getId().equals(excluded)) {
                byUser.put(user.getId(), new StockWitnessCandidateDTO(user.getId(), TransfusionMapper.userName(user)));
            }
        }
        return byUser.values().stream()
            .sorted(java.util.Comparator.comparing(StockWitnessCandidateDTO::displayName,
                java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    // ── The permanent flag ──────────────────────────────────────────────

    /** The newest exposure of the patient, at any hospital, or null: the flag follows the patient. */
    public OPositiveReleaseDecision newestExposure(UUID patientId) {
        if (patientId == null) {
            return null;
        }
        return decisionRepository.findByPatientIdAndRhdExposureTrueOrderByIssuedAtDesc(patientId).stream()
            .findFirst().orElse(null);
    }

    /**
     * {@link #newestExposure} as it is SHOWN to somebody acting at a hospital: when the flag rests on the record
     * of another hospital, the read is accounted as a cross-hospital one. The account never fails the read.
     */
    public OPositiveReleaseDecision newestExposureShownAt(UUID patientId, UUID actingHospitalId) {
        if (patientId == null) {
            return null;
        }
        List<OPositiveReleaseDecision> exposures =
            decisionRepository.findByPatientIdAndRhdExposureTrueOrderByIssuedAtDesc(patientId);
        if (reachRecorder != null && actingHospitalId != null && !exposures.isEmpty()) {
            try {
                List<UUID> sources = exposures.stream().map(row -> com.example.hms.service.recordaccess
                    .CrossHospitalReachRecorder.hospitalIdOf(row.getHospital())).toList();
                reachRecorder.recordReach(patientId, actingHospitalId, roleValidator.getCurrentUserId(), null,
                    com.example.hms.service.recordaccess.CrossHospitalReachRecorder.reachOf(sources, actingHospitalId),
                    REACH_DESCRIPTION);
            } catch (RuntimeException ex) {
                log.warn("Cross-hospital RhD exposure read not accounted for patient {} at hospital {}: {}",
                    patientId, actingHospitalId, ex.getClass().getSimpleName());
            }
        }
        return exposures.stream().findFirst().orElse(null);
    }

    // ── The protocol of the hospital ────────────────────────────────────

    public OPositiveProtocolDTO protocol(UUID hospitalId) {
        Settings settings = settings(hospitalId);
        return new OPositiveProtocolDTO(hospitalId, settings.protocolActive(), settings.femaleAgeCutoff(),
            settings.boysLowRisk(), settings.reviewDeadlineHours());
    }

    /** The biologist of the hospital turns the protocol on and sets it. Every change is audited with its values. */
    public OPositiveProtocolDTO setProtocol(Hospital hospital, OPositiveProtocolDTO update) {
        UUID hospitalId = hospital.getId();
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId,
                OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.protocol.biologistOnly"));
        }
        if (update.active() == null) {
            // An empty body must not switch a hospital's protocol off by accident.
            throw new FieldBusinessException("active", "transfusion.oPositive.protocol.activeRequired");
        }
        int cutoff = update.femaleAgeCutoff() == null ? OPositiveReleaseAuthority.DEFAULT_FEMALE_AGE_CUTOFF
            : update.femaleAgeCutoff();
        if (cutoff < OPositiveReleaseAuthority.MIN_FEMALE_AGE_CUTOFF || cutoff > OPositiveReleaseAuthority.MAX_FEMALE_AGE_CUTOFF) {
            throw new FieldBusinessException("femaleAgeCutoff", "transfusion.oPositive.protocol.cutoffRange",
                OPositiveReleaseAuthority.MIN_FEMALE_AGE_CUTOFF, OPositiveReleaseAuthority.MAX_FEMALE_AGE_CUTOFF);
        }
        int deadline = update.reviewDeadlineHours() == null ? OPositiveReleaseAuthority.DEFAULT_REVIEW_DEADLINE_HOURS
            : update.reviewDeadlineHours();
        if (deadline < OPositiveReleaseAuthority.MIN_REVIEW_DEADLINE_HOURS
                || deadline > OPositiveReleaseAuthority.MAX_REVIEW_DEADLINE_HOURS) {
            throw new FieldBusinessException("reviewDeadlineHours", "transfusion.oPositive.protocol.deadlineRange",
                OPositiveReleaseAuthority.MIN_REVIEW_DEADLINE_HOURS, OPositiveReleaseAuthority.MAX_REVIEW_DEADLINE_HOURS);
        }
        OPositiveReleaseProtocol protocol = protocolRepository.findByHospital_Id(hospitalId)
            .orElseGet(() -> OPositiveReleaseProtocol.builder().hospital(hospital).build());
        boolean active = Boolean.TRUE.equals(update.active());
        boolean boysLowRisk = Boolean.TRUE.equals(update.boysLowRisk());
        protocol.setActive(active);
        protocol.setFemaleAgeCutoff(cutoff);
        protocol.setBoysLowRisk(boysLowRisk);
        protocol.setReviewDeadlineHours(deadline);
        protocol.setUpdatedByUserId(caller);
        protocolRepository.save(protocol);
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.CONFIGURATION_CHANGED)
                    .status(AuditStatus.SUCCESS)
                    .userId(caller)
                    .hospitalId(hospitalId)
                    .entityType(PROTOCOL_ENTITY_TYPE)
                    .resourceId(hospitalId.toString())
                    .eventDescription("Emergency O positive release protocol changed")
                    .details(Map.of("active", String.valueOf(active), "femaleAgeCutoff", String.valueOf(cutoff),
                        "boysLowRisk", String.valueOf(boysLowRisk), "reviewDeadlineHours", String.valueOf(deadline)))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit not written for the O positive protocol of hospital {}: {}", hospitalId,
                    ex.getClass().getSimpleName());
            }
        });
        return new OPositiveProtocolDTO(hospitalId, active, cutoff, boysLowRisk, deadline);
    }
}
