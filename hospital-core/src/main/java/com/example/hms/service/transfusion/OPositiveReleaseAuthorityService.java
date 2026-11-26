package com.example.hms.service.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.EmergencyReleaseType;
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
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveFollowUpRequestDTO;
import com.example.hms.payload.dto.transfusion.OPositiveProtocolDTO;
import com.example.hms.payload.dto.transfusion.OPositiveReleaseDecisionDTO;
import com.example.hms.repository.OPositiveReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseProtocolRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.support.EmergencyReleasePolicy;
import com.example.hms.service.support.OPositiveReleaseAuthority;
import com.example.hms.service.support.OPositiveReleaseAuthority.ExposureState;
import com.example.hms.service.support.OPositiveReleaseAuthority.Settings;
import com.example.hms.service.support.OPositiveReleaseAuthority.Subject;
import com.example.hms.service.support.OPositiveReleaseAuthority.Tier;
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

    private static final String DECISION_ENTITY_TYPE = "O_POSITIVE_RELEASE_DECISION";
    private static final String PROTOCOL_ENTITY_TYPE = "HOSPITAL_O_POSITIVE_PROTOCOL";
    private static final String FIELD_AUTHORISER = "authoriserUserId";
    private static final String FIELD_BIOLOGIST = "biologistUserId";
    private static final String MSG_DECISION_NOT_FOUND = "transfusion.oPositive.decision.notFound";
    private static final int MAX_NOTE_LENGTH = 2000;

    /** What a reader acting at one hospital is told when the flag rests on the record of another. */
    public static final String REACH_DESCRIPTION =
        "Cross-hospital RhD positive exposure flag read (transfusion safety, shown whatever the sharing choice)";

    private final OPositiveReleaseProtocolRepository protocolRepository;
    private final OPositiveReleaseDecisionRepository decisionRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final OPositiveReleaseNotices notices;
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
         * The name the release row carries as answering for it: who decided. Null while the authority is owed:
         * the person who released the bag is its issuer, never its authoriser.
         */
        public UUID answerable() {
            return decidedBy;
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
        // Only an incompatible release has a second name: on the O positive form the one name IS the physician.
        UUID ordering = type == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE ? input.getOrderingPhysicianUserId() : null;
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
                // An incompatible release may name, beside its own authoriser, the physician who ordered O positive.
                UUID decider = ordering != null
                    ? physician(input, ordering, issuer, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES)
                    : physician(input, input.getAuthoriserUserId(), issuer, hospitalId, OPositiveReleaseAuthority.HIGH_RISK_DECIDER_ROLE_CODES);
                if (decider == null) {
                    yield new Authorisation(assessment, OPositiveDecisionBasis.AUTHORITY_PENDING, null, null, false, issuer);
                }
                // « The requesting physician is enough; the biologist or laboratory director if reachable »: the
                // director's authority is recorded as the director's, never as a physician's order.
                boolean byPhysician = assignmentRepository.existsActiveWitness(decider, hospitalId,
                    OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES);
                yield new Authorisation(assessment,
                    byPhysician ? OPositiveDecisionBasis.PHYSICIAN_ORDER : OPositiveDecisionBasis.BIOLOGIST_AUTHORITY,
                    decider, null, verbal, issuer);
            }
            case KNOWN_ANTIBODY -> {
                // The physician's seat is a physician's, whatever the release. An incompatible release has two
                // roles: who authorises it (a doctor or the laboratory director) and who ordered O positive. When
                // both are named, each is recorded in its seat; a director who authorises sits as the biologist.
                boolean incompatible = type == EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE;
                boolean directorAuthorises = incompatible && named != null
                    && !assignmentRepository.existsActiveWitness(named, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES);
                UUID physician;
                if (ordering != null) {
                    physician = physician(input, ordering, issuer, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES);
                } else {
                    physician = directorAuthorises ? null
                        : physician(input, input.getAuthoriserUserId(), issuer, hospitalId, OPositiveReleaseAuthority.PHYSICIAN_ROLE_CODES);
                }
                UUID biologist = biologist(input, issuer, physician, hospitalId);
                boolean authoriserIsBiologist = incompatible && named != null && !Objects.equals(named, physician)
                    && assignmentRepository.existsActiveWitness(named, hospitalId, OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES);
                if (biologist == null && authoriserIsBiologist) {
                    biologist = named;
                }
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
    private UUID physician(EmergencyReleaseRequestDTO input, UUID named, UUID issuer, UUID hospitalId, Set<String> roles) {
        if (Boolean.TRUE.equals(input.getPhysicianUnreachable()) && (named == null || Objects.equals(named, issuer))) {
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
     * Keeps the decision beside its release row. A bag that is not O positive red cells writes nothing. The
     * notices are owed by the row and delivered after the commit ({@link OPositiveReleaseNotices}): a notice
     * never holds or undoes a release, and one that cannot be delivered now is delivered by the sweep.
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
        // A bag released again is a new release with its own row: the one before it, if its bag was never hung,
        // is closed here, so nothing that happens to this release is read back onto that one.
        noteBagBack(unit.getId(), com.example.hms.enums.OPositiveBagDisposition.SUPERSEDED);
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
        // The row is the obligation: its notices are resolved and filed after the commit, in a transaction of
        // their own. Nothing about a reader is read here, so nothing about a reader can fail this release.
        UUID decisionId = saved.getId();
        TransactionCallbacks.afterCommit(() -> deliverNotices(decisionId));
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

    /** After the commit. A failure leaves the row owed: the sweep delivers its notices at its next pass. */
    private void deliverNotices(UUID decisionId) {
        try {
            notices.deliver(decisionId);
        } catch (RuntimeException ex) {
            log.warn("Notices of O positive release decision {} not delivered now, left to the sweep: {}", decisionId,
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
    public OPositiveReleaseDecisionDTO countersign(UUID decisionId, UUID hospitalId, boolean noOtherPersonReachable) {
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
        // A physician and a biologist together are two people: the biologist of this release, or the person who
        // reviewed it, does not also sign the physician's part.
        requireSecondPerson(decision, noOtherPersonReachable,
            Objects.equals(caller, decision.getBiologistUserId()) || Objects.equals(caller, decision.getReviewedByUserId()));
        decision.setCountersignedByUserId(caller);
        decision.setCountersignedAt(LocalDateTime.now(clock));
        decisionRepository.save(decision);
        resolveItems(decision.getId(), OPositiveReleaseNotices.COUNTERSIGN_REFERENCE_TYPE,
            OPositiveReleaseNotices.OVERDUE_REFERENCE_TYPE);
        auditAfterCommit(AuditEventType.O_POSITIVE_RELEASE_COUNTERSIGNED, decision, caller,
            "Emergency O positive release countersigned: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /**
     * Four eyes on a known antibody: the physician's signature and the biologist's review are two different
     * people. When the same person comes to the second step, it is refused with the way out named: stating that
     * nobody else can be reached, which is accepted and recorded on the row. The bag left long ago; this holds
     * nothing back.
     */
    private static void requireSecondPerson(OPositiveReleaseDecision decision, boolean noOtherPersonReachable,
                                            boolean samePerson) {
        if (decision.getRiskTier() != OPositiveRiskTier.KNOWN_ANTIBODY || !samePerson) {
            return;
        }
        if (!noOtherPersonReachable) {
            throw new FieldBusinessException("noOtherPersonReachable", "transfusion.oPositive.fourEyes.required");
        }
        decision.setFourEyesWaived(true);
    }

    /** The review of the biologist: the laboratory director of this hospital, once. */
    public OPositiveReleaseDecisionDTO review(UUID decisionId, UUID hospitalId, String note, boolean noOtherPersonReachable) {
        OPositiveReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId,
                OPositiveReleaseAuthority.BIOLOGIST_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.oPositive.review.biologistOnly"));
        }
        if (!decision.reviewOwed()) {
            throw new FieldBusinessException("decisionId", "transfusion.oPositive.review.alreadyReviewed");
        }
        // The physician who ordered or countersigned it does not also review it as the biologist.
        requireSecondPerson(decision, noOtherPersonReachable,
            Objects.equals(caller, decision.getCountersignedByUserId()) || Objects.equals(caller, decision.getDecidedByUserId()));
        decision.setReviewedByUserId(caller);
        decision.setReviewedAt(LocalDateTime.now(clock));
        decision.setReviewNote(bounded(note, "note"));
        decisionRepository.save(decision);
        resolveItems(decision.getId(), OPositiveReleaseNotices.REVIEW_REFERENCE_TYPE);
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
        ExposureState state = exposureStateOf(decision, decision.getRelease().getBloodUnit());
        if (state == ExposureState.NOT_GIVEN) {
            // The bag came back unused: nobody was exposed. The follow-up is closed by that fact, here if no
            // return wrote it yet, and the caller is shown the closed row instead of a refusal.
            if (decision.getDisposition() == null) {
                decision.setDisposition(com.example.hms.enums.OPositiveBagDisposition.RETURNED_UNUSED);
                decision.setDispositionAt(LocalDateTime.now(clock));
                decisionRepository.save(decision);
            }
            resolveItems(decision.getId(), OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE);
            return toDto(List.of(decision)).get(0);
        }
        boolean informs = Boolean.TRUE.equals(request.getPatientInformed()) && decision.getPatientInformedAt() == null;
        boolean decides = request.getAntiDDecision() != null;
        if (decides && decision.getAntiDDecision() != null) {
            throw new FieldBusinessException("antiDDecision", "transfusion.oPositive.followUp.alreadyDecided");
        }
        String prospective = bounded(request.getNote(), "note");
        if (state != ExposureState.CONFIRMED && (decides || Boolean.TRUE.equals(request.getPatientInformed()))) {
            // The exposure is documented once the bag is recorded as given: nothing is written as decided or told
            // about an exposure that may not happen. A note may be kept meanwhile; it completes nothing.
            throw new FieldBusinessException(decides ? "antiDDecision" : "patientInformed",
                "transfusion.oPositive.followUp.administrationNotConfirmed");
        }
        if (!informs && !decides && prospective == null) {
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
        String note = prospective;
        if (note != null) {
            decision.setFollowUpNote(decision.getFollowUpNote() == null ? note : decision.getFollowUpNote() + "\n" + note);
        }
        decisionRepository.save(decision);
        if (!decision.followUpOwed()) {
            resolveItems(decision.getId(), OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE);
        }
        auditAfterCommit(AuditEventType.O_POSITIVE_FOLLOW_UP_RECORDED, decision, caller,
            "RhD positive exposure follow-up recorded: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /**
     * An antibody screen was recorded for the patient: the follow-ups whose window has opened have their screen.
     * A screen before the window (the antibody takes about three months to form) leaves them waiting.
     */
    public void noteAntibodyScreen(UUID patientId, UUID hospitalId, LocalDateTime performedAt) {
        if (patientId == null || hospitalId == null || performedAt == null) {
            return;
        }
        // One statement on that column alone, at the hospital that recorded the screen: the row may be
        // countersigned or reviewed at the same moment, and another hospital's follow-up is its own.
        decisionRepository.markAntibodyScreenDone(patientId, hospitalId, performedAt, performedAt.toLocalDate());
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
        return decisionRepository.lockByIdAndHospital(decisionId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DECISION_NOT_FOUND, decisionId));
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** The releases of one hospital for its review queue, the newest first; only what is still owed when asked. */
    public List<OPositiveReleaseDecisionDTO> list(UUID hospitalId, boolean openOnly) {
        List<OPositiveReleaseDecisionDTO> rows = toDto(decisionRepository.findByHospital_IdOrderByIssuedAtDesc(hospitalId));
        return openOnly ? rows.stream().filter(OPositiveReleaseAuthorityService::owesSomething).toList() : rows;
    }

    /** A countersignature, a review, or the follow-up of a bag that was not returned unused. */
    private static boolean owesSomething(OPositiveReleaseDecisionDTO row) {
        boolean followUp = row.isFollowUpRequired() && !ExposureState.NOT_GIVEN.name().equals(row.getExposureState())
            && (row.getAntiDDecision() == null || row.getPatientInformedAt() == null || row.getAntibodyScreenDoneAt() == null);
        return row.getReviewedAt() == null || (row.getCountersignDueAt() != null && row.getCountersignedAt() == null) || followUp;
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
                .fourEyesWaived(row.isFourEyesWaived())
                .disposition(row.getDisposition() != null ? row.getDisposition().name() : null)
                .exposureState(row.isRhdExposure() ? exposureStateOf(row, unit).name() : null)
                .countersignCovering(row.getCountersignedByUserId() != null && row.getDecidedByUserId() != null
                    && !Objects.equals(row.getCountersignedByUserId(), row.getDecidedByUserId()))
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

    /**
     * What the flag says: when, where, and whether the bag was given ({@code confirmed}) or is out with no
     * administration on record.
     */
    public record RhdExposure(LocalDateTime issuedAt, UUID hospitalId, String hospitalName, boolean confirmed) {
    }

    private ExposureState exposureStateOf(OPositiveReleaseDecision decision, BloodUnit unit) {
        if (decision.getDisposition() != null) {
            return OPositiveReleaseAuthority.exposureState(decision.getDisposition(), false, false);
        }
        boolean administered = decisionRepository.countAdministrations(decision.getBloodUnitId(), decision.getRequestId()) > 0;
        UUID heldBy = unit != null && unit.getRequest() != null ? unit.getRequest().getId() : null;
        return OPositiveReleaseAuthority.exposureState(administered,
            OPositiveReleaseAuthority.stillOut(unit != null ? unit.getStatus() : null, heldBy, decision.getRequestId()));
    }

    // ── What became of the bag ──────────────────────────────────────────

    /**
     * The bag was hung on this request: the release still open for it is the one that was given. Written on the
     * release's own row, once; called where an administration starts.
     */
    public void noteAdministered(UUID bloodUnitId, UUID requestId, LocalDateTime at) {
        if (bloodUnitId != null && requestId != null) {
            decisionRepository.markAdministered(bloodUnitId, requestId, at);
        }
    }

    /** The bag came back to the laboratory, or was discarded, without being hung. */
    public void noteBagBack(UUID bloodUnitId) {
        noteBagBack(bloodUnitId, com.example.hms.enums.OPositiveBagDisposition.RETURNED_UNUSED);
    }

    /**
     * Closes the releases of this bag that are still open, as not given: nobody was exposed by them, no flag
     * rests on them, and their exposure follow-up is closed with that reason; the tasks filed for it leave the
     * in-baskets. The countersignature and the review of the release stay owed: the decision was still taken.
     */
    private void noteBagBack(UUID bloodUnitId, com.example.hms.enums.OPositiveBagDisposition disposition) {
        List<UUID> open = bloodUnitId == null ? List.of() : decisionRepository.findOpenIdsForUnit(bloodUnitId);
        if (open.isEmpty()) {
            return;
        }
        decisionRepository.markNotGiven(open, disposition, LocalDateTime.now(clock));
        open.forEach(id -> resolveItems(id, OPositiveReleaseNotices.FOLLOW_UP_REFERENCE_TYPE));
    }

    /**
     * The exposure of the patient as it is SHOWN to somebody acting at a hospital, read at EVERY hospital: the
     * flag follows the patient. The newest release whose bag was given; failing that the newest still out
     * (« administration not confirmed »); a bag that came back unused exposed nobody and shows nothing. When the
     * flag rests on the record of another hospital, the read is accounted as a cross-hospital one; the account
     * never fails the read. Null when there is nothing to show.
     */
    public RhdExposure exposureShownAt(UUID patientId, UUID actingHospitalId) {
        if (patientId == null) {
            return null;
        }
        RhdExposure confirmed = null;
        RhdExposure unconfirmed = null;
        List<UUID> sources = new ArrayList<>();
        for (OPositiveReleaseDecisionRepository.ExposureRow row : decisionRepository.findExposures(patientId)) {
            ExposureState state = OPositiveReleaseAuthority.exposureState(row.getDisposition(), row.getAdministrations() > 0,
                OPositiveReleaseAuthority.stillOut(row.getUnitStatus(), row.getUnitRequestId(), row.getRequestId()));
            if (state == ExposureState.NOT_GIVEN) {
                continue;
            }
            sources.add(row.getHospitalId());
            RhdExposure exposure = new RhdExposure(row.getIssuedAt(), row.getHospitalId(), row.getHospitalName(),
                state == ExposureState.CONFIRMED);
            if (state == ExposureState.CONFIRMED && confirmed == null) {
                confirmed = exposure;
            } else if (state == ExposureState.UNCONFIRMED && unconfirmed == null) {
                unconfirmed = exposure;
            }
        }
        if (reachRecorder != null && actingHospitalId != null && !sources.isEmpty()) {
            try {
                reachRecorder.recordReach(patientId, actingHospitalId, roleValidator.getCurrentUserId(), null,
                    com.example.hms.service.recordaccess.CrossHospitalReachRecorder.reachOf(sources, actingHospitalId),
                    REACH_DESCRIPTION);
            } catch (RuntimeException ex) {
                log.warn("Cross-hospital RhD exposure read not accounted for patient {} at hospital {}: {}",
                    patientId, actingHospitalId, ex.getClass().getSimpleName());
            }
        }
        return confirmed != null ? confirmed : unconfirmed;
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
