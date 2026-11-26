package com.example.hms.service.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.BloodUnitStatus;
import com.example.hms.enums.IncompatibleAntibodyStatus;
import com.example.hms.enums.IncompatibleMonitoringCheck;
import com.example.hms.enums.IncompatibleMonitoringOutcome;
import com.example.hms.enums.IncompatibleReleaseBasis;
import com.example.hms.enums.PatientInformedStatus;
import com.example.hms.enums.RhFactor;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.EmergencyBloodRelease;
import com.example.hms.model.Hospital;
import com.example.hms.model.IncompatibleReleaseDecision;
import com.example.hms.model.OPositiveReleaseProtocol;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseOptionsDTO;
import com.example.hms.payload.dto.transfusion.EmergencyReleaseRequestDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleMonitoringRequestDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleReleaseDecisionDTO;
import com.example.hms.payload.dto.transfusion.IncompatibleReleaseProtocolDTO;
import com.example.hms.repository.IncompatibleReleaseDecisionRepository;
import com.example.hms.repository.OPositiveReleaseProtocolRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionCrossmatchRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.support.IncompatibleReleaseAuthority;
import com.example.hms.service.support.IncompatibleReleaseAuthority.Candidate;
import com.example.hms.service.support.IncompatibleReleaseAuthority.Settings;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The incompatible-release part of the ONE release decision service (owner decision 2026-11-26, option B; rule set
 * INCOMPATIBLE_RELEASE_AUTHORITY, « provisoire »). {@link OPositiveReleaseAuthorityService} is what every release
 * path asks; for a release despite an incompatible crossmatch it asks this class, and nothing else may
 * ({@link #authorise} and {@link #record} are package-private, {@code IncompatibleReleaseAuthorityGuardTest}).
 *
 * <p>Two things here are equally hard rules:
 * <ul>
 *   <li><b>Never an ABO-excluded bag.</b> {@link #requireAboCompatible} is the first thing {@link #authorise}
 *       does, before any name or setting is read, and the row cannot be written without it
 *       ({@code ck_incompatible_decision_abo}).</li>
 *   <li><b>Never hold blood back for want of a person</b>, where the hospital switched the fallback on: the
 *       issuer states that no physician can be reached, and the least incompatible bag goes out in the same
 *       submit, with the authority owed. Nothing about a reader of the alerts is read in the release
 *       transaction, so nothing about a reader can fail a release.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.INCOMPATIBLE_RELEASE_AUTHORITY)
public class IncompatibleReleaseDecisions {

    private static final String DECISION_ENTITY_TYPE = "INCOMPATIBLE_RELEASE_DECISION";
    private static final String PROTOCOL_ENTITY_TYPE = "HOSPITAL_INCOMPATIBLE_RELEASE_PROTOCOL";
    private static final String FIELD_UNIT = "bloodUnitId";
    private static final String FIELD_AUTHORISER = "authoriserUserId";
    private static final String FIELD_UNREACHABLE = "physicianUnreachable";
    private static final String FIELD_DECISION = "decisionId";
    private static final String MSG_DECISION_NOT_FOUND = "transfusion.incompatible.decision.notFound";
    private static final int MAX_TEXT_LENGTH = 2000;

    private final IncompatibleReleaseDecisionRepository incompatibleRows;
    private final OPositiveReleaseProtocolRepository protocolRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientBloodGroupRepository bloodGroupRepository;
    private final TransfusionCrossmatchRepository crossmatchRepository;
    private final TransfusionAdministrationRepository administrationRepository;
    private final UserRepository userRepository;
    private final PositiveAntibodyScreenService antibodyScreen;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final IncompatibleReleaseNotices notices;
    private final Clock clock;

    // ── The never event ─────────────────────────────────────────────────

    /**
     * THE HARD BLOCK, on every path: a red-cell bag whose ABO group the patient's group excludes does not leave,
     * whoever asks, whatever the urgency and whatever the hospital switched on. Group O suits everybody; any
     * other group needs the group of the patient on record. Nothing else of a bag is judged here.
     */
    public void requireAboCompatible(AboGroup recipientAbo, BloodUnit unit) {
        if (AboGroup.isEmergencyRedCells(unit.getProductType())
                && !IncompatibleReleaseAuthority.aboAllows(recipientAbo, unit.getAboGroup(), unit.getProductType())) {
            throw new FieldBusinessException(FIELD_UNIT, "transfusion.emergency.groupExcludes", unit.getUnitNumber());
        }
    }

    // ── One release ─────────────────────────────────────────────────────

    /**
     * What one incompatible release was allowed on. Built by {@link #authorise} only: holding one is the proof
     * that the ABO block and the authority rule both saw the release.
     */
    public static final class Grant {
        private final IncompatibleReleaseBasis basis;
        private final UUID issuer;
        private final UUID authoriser;
        private final String declarerRole;
        private final String unreachableReason;
        private final String unitChoiceReason;
        private final PatientInformedStatus patientInformed;
        private final Settings settings;
        private final PatientBloodGroup screen;
        private final TransfusionCrossmatch crossmatch;
        private final Candidate chosen;
        private final boolean leastIncompatible;
        private final int candidatesConsidered;

        private Grant(IncompatibleReleaseBasis basis, UUID issuer, UUID authoriser, String declarerRole,
                      String unreachableReason, String unitChoiceReason, PatientInformedStatus patientInformed,
                      Settings settings, PatientBloodGroup screen, TransfusionCrossmatch crossmatch, Candidate chosen,
                      boolean leastIncompatible, int candidatesConsidered) {
            this.basis = basis;
            this.issuer = issuer;
            this.authoriser = authoriser;
            this.declarerRole = declarerRole;
            this.unreachableReason = unreachableReason;
            this.unitChoiceReason = unitChoiceReason;
            this.patientInformed = patientInformed;
            this.settings = settings;
            this.screen = screen;
            this.crossmatch = crossmatch;
            this.chosen = chosen;
            this.leastIncompatible = leastIncompatible;
            this.candidatesConsidered = candidatesConsidered;
        }

        public IncompatibleReleaseBasis basis() {
            return basis;
        }

        /** The physician or laboratory director named; null on the fallback: the issuer is never the authoriser. */
        public UUID authoriser() {
            return authoriser;
        }
    }

    /**
     * On whose authority this bag goes out despite its incompatible crossmatch. Called by the release decision
     * service only, before the bag is put out.
     *
     * <p>The order is the rule: the ABO block first; then what the record needs on every release (why this bag,
     * whether the patient was told, and a name or the declaration), asked together; then the authority. A
     * physician named is the normal path. With nobody named, the issuer's statement that no physician can be
     * reached releases the bag only where the hospital switched the fallback on, the issuer holds a role the
     * hospital allows to declare it, the statement has its reason, and no releasable bag is less incompatible
     * than the one picked.
     */
    Grant authorise(TransfusionRequest request, BloodUnit unit, EmergencyReleaseRequestDTO input) {
        PatientBloodGroup screen = currentScreen(request);
        requireAboCompatible(screen != null ? screen.getAboGroup() : null, unit);
        UUID issuer = roleValidator.getCurrentUserId();
        UUID hospitalId = request.getHospital().getId();
        TransfusionCrossmatch crossmatch = issuer == null ? null : crossmatchRepository
            .findByRequest_IdAndBloodUnit_Id(request.getId(), unit.getId())
            .filter(result -> !Boolean.TRUE.equals(result.getCompatible())).orElse(null);
        // The bag being released is ranked from its own result: it is a candidate whatever the list says. No result,
        // or (outside group O) an RhD the group of the patient excludes: the documented release never sends it.
        Candidate chosen = java.util.Optional.ofNullable(crossmatch)
            .map(result -> candidateOf(unit, result, screen))
            .orElseThrow(() -> new FieldBusinessException(FIELD_UNIT,
                crossmatch == null ? "transfusion.emergency.noIncompatibleResult" : "transfusion.emergency.groupExcludes",
                unit.getUnitNumber()));
        Settings settings = settings(hospitalId);
        UUID named = input.getAuthoriserUserId();
        boolean declares = named == null && Boolean.TRUE.equals(input.getPhysicianUnreachable());
        // What every release states and what the declaration adds, asked together: one refusal names every field
        // left empty. The reason of the declaration is asked only where the hospital accepts a declaration at all.
        RequiredFields.check()
            .requireValue(input.getUnitChoiceReason(), "unitChoiceReason", "transfusion.incompatible.unitChoiceReasonRequired")
            .requireValue(input.getPatientInformation(), "patientInformation", "transfusion.incompatible.patientInformedRequired")
            .require(named != null || declares, FIELD_AUTHORISER, "transfusion.emergency.authoriserRequired")
            .require(!declares || !settings.fallbackEnabled() || StringUtils.hasText(input.getUnreachableReason()),
                "unreachableReason", "transfusion.incompatible.fallback.reasonRequired")
            .orThrow();
        String choice = bounded(input.getUnitChoiceReason(), "unitChoiceReason");
        Ranking ranking = ranking(request, screen, chosen);
        List<Candidate> better = IncompatibleReleaseAuthority.better(chosen, ranking.candidates());
        if (named != null) {
            requireAnswerableAuthoriser(named, issuer, hospitalId);
            return new Grant(IncompatibleReleaseBasis.NAMED_PHYSICIAN, issuer, named, null, null, choice,
                input.getPatientInformation(), settings, screen, crossmatch, chosen, better.isEmpty(), ranking.candidates().size());
        }
        if (!settings.fallbackEnabled()) {
            throw new FieldBusinessException(FIELD_UNREACHABLE, "transfusion.incompatible.fallback.disabled");
        }
        String declarerRole = java.util.Optional.ofNullable(declarerRole(issuer, hospitalId, settings))
            .orElseThrow(() -> new FieldBusinessException(FIELD_UNREACHABLE, "transfusion.incompatible.fallback.declarerNotAllowed"));
        String why = bounded(input.getUnreachableReason(), "unreachableReason");
        if (!better.isEmpty()) {
            // Not a refusal of blood: a less incompatible bag is on the bench, and it is named.
            throw new FieldBusinessException(FIELD_UNIT, "transfusion.incompatible.fallback.notLeastIncompatible",
                unit.getUnitNumber(), String.join(", ", better.stream().map(Candidate::unitNumber).toList()));
        }
        return new Grant(IncompatibleReleaseBasis.AUTHORITY_PENDING, issuer, null, declarerRole, why, choice,
            input.getPatientInformation(), settings, screen, crossmatch, chosen, true, ranking.candidates().size());
    }

    /** The normal path: a doctor or the laboratory director of this hospital, who is not the person issuing. */
    private void requireAnswerableAuthoriser(UUID named, UUID issuer, UUID hospitalId) {
        if (Objects.equals(named, issuer)) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.authoriserNotIssuer");
        }
        if (!assignmentRepository.existsActiveWitness(named, hospitalId, IncompatibleReleaseAuthority.AUTHORISER_ROLE_CODES)) {
            throw new FieldBusinessException(FIELD_AUTHORISER, "transfusion.emergency.authoriserInvalid");
        }
    }

    /** The first role of the hospital's setting the issuer holds here, live; null when none. */
    private String declarerRole(UUID issuer, UUID hospitalId, Settings settings) {
        for (String role : IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE) {
            if (settings.declarerRoles().contains(role)
                    && assignmentRepository.existsActiveWitness(issuer, hospitalId, Set.of(role))) {
                return role;
            }
        }
        return null;
    }

    private static String bounded(String text, String field) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new FieldBusinessException(field, "transfusion.incompatible.textTooLong", MAX_TEXT_LENGTH);
        }
        return trimmed;
    }

    /** The current blood group of the patient at the hospital of the request; the one on the request failing that. */
    private PatientBloodGroup currentScreen(TransfusionRequest request) {
        if (request.getPatient() == null || request.getHospital() == null) {
            return request.getBloodGroup();
        }
        return bloodGroupRepository
            .findByPatient_IdAndHospital_IdAndSupersededFalse(request.getPatient().getId(), request.getHospital().getId())
            .orElse(request.getBloodGroup());
    }

    // ── Least incompatible ──────────────────────────────────────────────

    /** The bags with an incompatible result on a request that it could release, ranked. */
    public record Ranking(List<Candidate> candidates) {

        public Candidate candidate(UUID unitId) {
            return candidates.stream().filter(candidate -> candidate.unitId().equals(unitId)).findFirst().orElse(null);
        }

        /** No candidate ranks better than this bag. False for a bag that is not a candidate. */
        public boolean least(UUID unitId) {
            Candidate chosen = candidate(unitId);
            return chosen != null && IncompatibleReleaseAuthority.better(chosen, candidates).isEmpty();
        }
    }

    /** The ranking the release form is shown: every bag of the request that could still go out. */
    public Ranking ranking(TransfusionRequest request) {
        return ranking(request, currentScreen(request), null);
    }

    /**
     * One bag as the ranking reads it, or null when the group of the patient does not let a documented release
     * send it: an ABO group the patient excludes, no RhD on the bag, or (outside group O) an RhD the patient
     * excludes.
     */
    private static Candidate candidateOf(BloodUnit bag, TransfusionCrossmatch result, PatientBloodGroup screen) {
        AboGroup recipientAbo = screen != null ? screen.getAboGroup() : null;
        RhFactor recipientRh = screen != null ? screen.getRhFactor() : null;
        boolean dMatched = IncompatibleReleaseAuthority.dMatched(recipientAbo, recipientRh, bag.getAboGroup(),
            bag.getRhFactor(), bag.getProductType());
        boolean groupAllows = IncompatibleReleaseAuthority.aboAllows(recipientAbo, bag.getAboGroup(), bag.getProductType())
            && bag.getRhFactor() != null
            && (bag.getAboGroup() == AboGroup.emergencyReleaseGroup() || dMatched);
        return groupAllows
            ? new Candidate(bag.getId(), bag.getUnitNumber(), dMatched, result.getRhKellMatched(), result.getAntigenNegative())
            : null;
    }

    /**
     * A candidate is a bag with an incompatible result on this request that this request could release: of its
     * product, in date, not hung, not out already, not held for another request, of an ABO group the patient
     * accepts, and (outside group O) of an RhD the patient accepts. A bag that cannot go out is never « better »
     * than one that can: it must not stand in the way of the one bag there is.
     *
     * @param chosen the bag being released, already ranked from its own result; null for the form's list
     */
    private Ranking ranking(TransfusionRequest request, PatientBloodGroup screen, Candidate chosen) {
        LocalDate today = LocalDate.now(clock);
        List<Candidate> candidates = new ArrayList<>();
        if (chosen != null) {
            candidates.add(chosen);
        }
        for (TransfusionCrossmatch result : crossmatchRepository.findByRequest_IdOrderByPerformedAtDesc(request.getId())) {
            BloodUnit bag = result.getBloodUnit();
            if (Boolean.TRUE.equals(result.getCompatible()) || bag == null
                    || (chosen != null && bag.getId().equals(chosen.unitId()))) {
                continue;
            }
            boolean heldElsewhere = bag.getRequest() != null && !Objects.equals(bag.getRequest().getId(), request.getId())
                && (bag.getStatus() == BloodUnitStatus.CROSSMATCHED || bag.getStatus() == BloodUnitStatus.ISSUED);
            boolean available = bag.getProductType() == request.getProductType() && !bag.isTerminal()
                && !bag.isExpiredOn(today) && !heldElsewhere && bag.getStatus() != BloodUnitStatus.ISSUED
                && administrationRepository.findByBloodUnit_Id(bag.getId()).isEmpty();
            Candidate candidate = available ? candidateOf(bag, result, screen) : null;
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return new Ranking(candidates);
    }

    /** What the release form is told, so it offers the fallback only where the server will accept it. */
    public EmergencyReleaseOptionsDTO.IncompatibleRule ruleFor(UUID hospitalId) {
        Settings settings = settings(hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        return EmergencyReleaseOptionsDTO.IncompatibleRule.builder()
            .fallbackEnabled(settings.fallbackEnabled())
            .callerMayDeclare(settings.fallbackEnabled() && caller != null && declarerRole(caller, hospitalId, settings) != null)
            .countersignDeadlineHours(settings.countersignDeadlineHours())
            .build();
    }

    // ── The row ─────────────────────────────────────────────────────────

    /**
     * Keeps the decision beside its release row. The notices are owed by the row and delivered after the commit
     * ({@link IncompatibleReleaseNotices}): a notice never holds or undoes a release, and one that cannot be
     * delivered now is delivered by the sweep. Nothing about a reader is read here.
     */
    IncompatibleReleaseDecision record(EmergencyBloodRelease release, Grant grant, TransfusionRequest request, BloodUnit unit) {
        // A bag released again is a new release with its own row: the one before it, never hung, is closed here.
        noteBagBack(unit.getId());
        LocalDateTime issuedAt = release.getIssuedAt();
        Patient patient = request.getPatient();
        PatientBloodGroup screen = grant.screen;
        boolean antibodyKnown = antibodyOnRecord(request, screen);
        boolean fallback = grant.basis == IncompatibleReleaseBasis.AUTHORITY_PENDING;
        IncompatibleReleaseDecision saved = incompatibleRows.save(IncompatibleReleaseDecision.builder()
            .release(release)
            .requestId(request.getId())
            .bloodUnitId(unit.getId())
            .patientId(patient.getId())
            .hospital(request.getHospital())
            .decisionBasis(grant.basis)
            .fallbackEnabled(grant.settings.fallbackEnabled())
            .countersignDeadlineHours(grant.settings.countersignDeadlineHours())
            .issuedByUserId(grant.issuer)
            .authoriserUserId(grant.authoriser)
            .declarerRole(grant.declarerRole)
            .unreachableReason(grant.unreachableReason)
            .antibodyStatus(antibodyKnown ? IncompatibleAntibodyStatus.ANTIBODY_ON_RECORD : IncompatibleAntibodyStatus.NO_ANTIBODY_ON_RECORD)
            .antibodyDetail(antibodyDetail(request, screen))
            .crossmatchFinding(grant.crossmatch.getIncompatibilityReason())
            .recipientAbo(screen != null && screen.getAboGroup() != null ? screen.getAboGroup().name() : null)
            .recipientRh(screen != null && screen.getRhFactor() != null ? screen.getRhFactor().name() : null)
            .unitAbo(unit.getAboGroup().name())
            .unitRh(unit.getRhFactor() != null ? unit.getRhFactor().name() : null)
            // Never assumed: computed again from the two groups, and the database refuses a row where it is false.
            .aboCompatible(IncompatibleReleaseAuthority.aboAllows(screen != null ? screen.getAboGroup() : null,
                unit.getAboGroup(), unit.getProductType()))
            .rhdMatched(grant.chosen.dMatched())
            .rhKellMatched(grant.chosen.rhKellMatched())
            .antigenNegative(grant.chosen.antigenNegative())
            .leastIncompatible(grant.leastIncompatible)
            .candidatesConsidered(grant.candidatesConsidered)
            .unitChoiceReason(grant.unitChoiceReason)
            .patientInformed(grant.patientInformed)
            .issuedAt(issuedAt)
            .countersignDueAt(fallback ? issuedAt.plusHours(grant.settings.countersignDeadlineHours()) : null)
            .build());
        UUID decisionId = saved.getId();
        TransactionCallbacks.afterCommit(() -> deliverNotices(decisionId));
        auditAfterCommit(AuditEventType.INCOMPATIBLE_RELEASE_DECIDED, saved, grant.issuer,
            "Incompatible release decision recorded for blood unit " + saved.getBloodUnitId()
                + " against transfusion request " + saved.getRequestId());
        return saved;
    }

    /** A positive screen at this hospital, or the permanent flag of the patient at any hospital. */
    private boolean antibodyOnRecord(TransfusionRequest request, PatientBloodGroup screen) {
        return (screen != null && screen.getAntibodyScreen() == AntibodyScreenResult.POSITIVE)
            || antibodyScreen.alloImmunised(request.getPatient().getId());
    }

    /** What the laboratory wrote with the newest positive screen of the patient here, when it wrote anything. */
    private String antibodyDetail(TransfusionRequest request, PatientBloodGroup screen) {
        if (screen != null && screen.getAntibodyScreen() == AntibodyScreenResult.POSITIVE) {
            return screen.getAntibodyDetail();
        }
        return bloodGroupRepository
            .findByPatient_IdAndHospital_IdOrderByPerformedAtDesc(request.getPatient().getId(), request.getHospital().getId())
            .stream().filter(group -> group.getAntibodyScreen() == AntibodyScreenResult.POSITIVE)
            // The screen first, then its detail: the detail is optional, and a stream cannot carry a null to findFirst.
            .findFirst().map(PatientBloodGroup::getAntibodyDetail).orElse(null);
    }

    /** After the commit. A failure leaves the row owed: the sweep delivers its notices at its next pass. */
    private void deliverNotices(UUID decisionId) {
        try {
            notices.deliver(decisionId);
        } catch (RuntimeException ex) {
            log.warn("Notices of incompatible release decision {} not delivered now, left to the sweep: {}", decisionId,
                ex.getClass().getSimpleName());
        }
    }

    private void auditAfterCommit(AuditEventType type, IncompatibleReleaseDecision decision, UUID actorUserId, String description) {
        UUID hospitalId = decision.getHospital().getId();
        UUID decisionId = decision.getId();
        // Codes only, no name and no narrative.
        Map<String, String> details = new LinkedHashMap<>();
        details.put("decisionBasis", decision.getDecisionBasis().name());
        details.put("antibodyStatus", decision.getAntibodyStatus().name());
        details.put("leastIncompatible", String.valueOf(decision.isLeastIncompatible()));
        details.put("patientInformed", decision.getPatientInformed().name());
        if (decision.getDeclarerRole() != null) {
            details.put("declarerRole", decision.getDeclarerRole());
        }
        if (decision.getMonitoringOutcome() != null) {
            details.put("monitoringOutcome", decision.getMonitoringOutcome().name());
        }
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
                log.warn("Audit {} not written for incompatible release decision {}: {}", type, decisionId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    // ── Countersignature and monitoring ─────────────────────────────────

    /**
     * A physician or the laboratory director of the hospital takes the decision the fallback left owed: anybody
     * the normal path would accept as authoriser, and never the person who issued the bag.
     */
    public IncompatibleReleaseDecisionDTO countersign(UUID decisionId, UUID hospitalId) {
        IncompatibleReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (!decision.countersignOwed()) {
            throw new FieldBusinessException(FIELD_DECISION, "transfusion.incompatible.countersign.notOwed");
        }
        if (caller == null || Objects.equals(caller, decision.getIssuedByUserId())) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.incompatible.countersign.notIssuer"));
        }
        if (!assignmentRepository.existsActiveWitness(caller, hospitalId, IncompatibleReleaseAuthority.AUTHORISER_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.incompatible.countersign.physicianOnly"));
        }
        decision.setCountersignedByUserId(caller);
        decision.setCountersignedAt(LocalDateTime.now(clock));
        incompatibleRows.save(decision);
        resolveItems(decision.getId(), IncompatibleReleaseNotices.COUNTERSIGN_REFERENCE_TYPE,
            IncompatibleReleaseNotices.OVERDUE_REFERENCE_TYPE);
        auditAfterCommit(AuditEventType.INCOMPATIBLE_RELEASE_COUNTERSIGNED, decision, caller,
            "Incompatible release countersigned: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    /**
     * The clinician records the checks done, what the monitoring found, and that the patient was told. The checks
     * and the note may come in several times; the outcome closes the monitoring, once, and only for a bag that
     * was hung: nobody states the absence of a reaction to a transfusion that is not on record.
     */
    public IncompatibleReleaseDecisionDTO recordMonitoring(UUID decisionId, UUID hospitalId, IncompatibleMonitoringRequestDTO request) {
        IncompatibleReleaseDecision decision = lockScoped(decisionId, hospitalId);
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId, IncompatibleReleaseNotices.MONITOR_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.incompatible.monitoring.clinicianOnly"));
        }
        IncompatibleMonitoringOutcome outcome = request.getOutcome();
        boolean informs = Boolean.TRUE.equals(request.getPatientInformed())
            && decision.getPatientInformed() == PatientInformedStatus.NOT_POSSIBLE && decision.getPatientInformedAt() == null;
        Set<IncompatibleMonitoringCheck> checks = checksOf(decision);
        int before = checks.size();
        if (request.getChecksDone() != null) {
            request.getChecksDone().stream().filter(Objects::nonNull).forEach(checks::add);
        }
        String note = bounded(request.getNote(), "note");
        if (outcome == null && !informs && checks.size() == before && note == null) {
            throw new FieldBusinessException("outcome", "transfusion.incompatible.monitoring.nothingRecorded");
        }
        if (outcome != null) {
            if (!decision.monitoringOwed()) {
                throw new FieldBusinessException("outcome", "transfusion.incompatible.monitoring.alreadyRecorded");
            }
            if (outcome == IncompatibleMonitoringOutcome.NOT_TRANSFUSED
                    || incompatibleRows.countAdministrations(decision.getBloodUnitId(), decision.getRequestId()) == 0) {
                // Whether the bag was given is the record's to say: the return of the bag closes this by itself.
                throw new FieldBusinessException("outcome", "transfusion.incompatible.monitoring.administrationNotConfirmed");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        decision.setMonitoringChecks(checks.isEmpty() ? null
            : String.join(",", checks.stream().map(Enum::name).toList()));
        if (note != null) {
            decision.setMonitoringNote(decision.getMonitoringNote() == null ? note : decision.getMonitoringNote() + "\n" + note);
        }
        if (informs) {
            decision.setPatientInformedAt(now);
            decision.setPatientInformedByUserId(caller);
        }
        if (outcome != null) {
            decision.setMonitoringOutcome(outcome);
            decision.setMonitoringRecordedByUserId(caller);
            decision.setMonitoringRecordedAt(now);
        }
        incompatibleRows.save(decision);
        if (outcome != null) {
            resolveItems(decision.getId(), IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE,
                IncompatibleReleaseNotices.HAEMOVIGILANCE_REFERENCE_TYPE);
        }
        auditAfterCommit(AuditEventType.INCOMPATIBLE_RELEASE_MONITORING_RECORDED, decision, caller,
            "Incompatible release monitoring recorded: decision " + decision.getId());
        return toDto(List.of(decision)).get(0);
    }

    private static Set<IncompatibleMonitoringCheck> checksOf(IncompatibleReleaseDecision decision) {
        Set<IncompatibleMonitoringCheck> checks = EnumSet.noneOf(IncompatibleMonitoringCheck.class);
        if (StringUtils.hasText(decision.getMonitoringChecks())) {
            for (String code : decision.getMonitoringChecks().split(",")) {
                checks.add(IncompatibleMonitoringCheck.valueOf(code.trim()));
            }
        }
        return checks;
    }

    /**
     * The bag came back to the laboratory, was discarded, or goes out again, without having been hung on the
     * request of the release: there is nothing to monitor, and the tasks filed for it leave the in-baskets. The
     * countersignature of a fallback release stays owed: the decision was still taken.
     */
    public void noteBagBack(UUID bloodUnitId) {
        if (bloodUnitId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (IncompatibleReleaseDecision open : incompatibleRows.findOpenForUnit(bloodUnitId)) {
            if (incompatibleRows.countAdministrations(open.getBloodUnitId(), open.getRequestId()) > 0) {
                continue;
            }
            open.setMonitoringOutcome(IncompatibleMonitoringOutcome.NOT_TRANSFUSED);
            open.setMonitoringRecordedAt(now);
            incompatibleRows.save(open);
            resolveItems(open.getId(), IncompatibleReleaseNotices.MONITORING_REFERENCE_TYPE,
                IncompatibleReleaseNotices.HAEMOVIGILANCE_REFERENCE_TYPE);
        }
    }

    private void resolveItems(UUID decisionId, String... referenceTypes) {
        for (String referenceType : referenceTypes) {
            inBasketService.removeOpenItemsForReference(decisionId, referenceType);
        }
    }

    private IncompatibleReleaseDecision lockScoped(UUID decisionId, UUID hospitalId) {
        return incompatibleRows.lockByIdAndHospital(decisionId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_DECISION_NOT_FOUND, decisionId));
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** The releases of one hospital, the newest first; only what is still owed when asked. */
    public List<IncompatibleReleaseDecisionDTO> list(UUID hospitalId, boolean openOnly) {
        List<IncompatibleReleaseDecision> rows = incompatibleRows.findByHospital_IdOrderByIssuedAtDesc(hospitalId);
        return toDto(openOnly ? rows.stream().filter(row -> row.countersignOwed() || row.monitoringOwed()).toList() : rows);
    }

    /** The decisions of these release rows, by release id, for the request detail. */
    public Map<UUID, IncompatibleReleaseDecisionDTO> byRelease(Collection<UUID> releaseIds) {
        Map<UUID, IncompatibleReleaseDecisionDTO> byRelease = new LinkedHashMap<>();
        if (releaseIds == null || releaseIds.isEmpty()) {
            return byRelease;
        }
        for (IncompatibleReleaseDecisionDTO dto : toDto(incompatibleRows.findByRelease_IdIn(releaseIds))) {
            byRelease.put(dto.getReleaseId(), dto);
        }
        return byRelease;
    }

    private List<IncompatibleReleaseDecisionDTO> toDto(List<IncompatibleReleaseDecision> rows) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (IncompatibleReleaseDecision row : rows) {
            for (UUID id : Arrays.asList(row.getIssuedByUserId(), row.getAuthoriserUserId(), row.getCountersignedByUserId(),
                    row.getPatientInformedByUserId(), row.getMonitoringRecordedByUserId())) {
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
        List<IncompatibleReleaseDecisionDTO> dtos = new ArrayList<>();
        for (IncompatibleReleaseDecision row : rows) {
            EmergencyBloodRelease release = row.getRelease();
            BloodUnit unit = release != null ? release.getBloodUnit() : null;
            Patient patient = release != null && release.getRequest() != null ? release.getRequest().getPatient() : null;
            dtos.add(IncompatibleReleaseDecisionDTO.builder()
                .id(row.getId())
                .releaseId(release != null ? release.getId() : null)
                .requestId(row.getRequestId())
                .bloodUnitId(row.getBloodUnitId())
                .unitNumber(unit != null ? unit.getUnitNumber() : null)
                .patientId(row.getPatientId())
                .patientName(patient != null ? patient.getFullName() : null)
                .decisionBasis(row.getDecisionBasis())
                .fallback(row.getDecisionBasis() == IncompatibleReleaseBasis.AUTHORITY_PENDING)
                .countersignDeadlineHours(row.getCountersignDeadlineHours())
                .issuedByName(names.get(row.getIssuedByUserId()))
                .authoriserName(names.get(row.getAuthoriserUserId()))
                .declarerRole(row.getDeclarerRole())
                .unreachableReason(row.getUnreachableReason())
                .authorityTiming(authorityTiming(row))
                .antibodyStatus(row.getAntibodyStatus())
                .antibodyDetail(row.getAntibodyDetail())
                .crossmatchFinding(row.getCrossmatchFinding())
                .recipientAbo(row.getRecipientAbo())
                .recipientRh(row.getRecipientRh())
                .unitAbo(row.getUnitAbo())
                .unitRh(row.getUnitRh())
                .rhdMatched(row.isRhdMatched())
                .rhKellMatched(row.getRhKellMatched())
                .antigenNegative(row.getAntigenNegative())
                .leastIncompatible(row.isLeastIncompatible())
                .candidatesConsidered(row.getCandidatesConsidered())
                .unitChoiceReason(row.getUnitChoiceReason())
                .patientInformed(row.getPatientInformed())
                .patientInformedAt(row.getPatientInformedAt())
                .patientInformedByName(names.get(row.getPatientInformedByUserId()))
                .issuedAt(row.getIssuedAt())
                .countersignDueAt(row.getCountersignDueAt())
                .countersignedByName(names.get(row.getCountersignedByUserId()))
                .countersignedAt(row.getCountersignedAt())
                .countersignOverdue(row.countersignOwed() && row.getCountersignDueAt().isBefore(now))
                .countersignEscalatedAt(row.getCountersignEscalatedAt())
                .monitoringChecklist(List.of(IncompatibleMonitoringCheck.values()))
                .monitoringChecksDone(List.copyOf(checksOf(row)))
                .monitoringOutcome(row.getMonitoringOutcome())
                .monitoringNote(row.getMonitoringNote())
                .monitoringRecordedByName(names.get(row.getMonitoringRecordedByUserId()))
                .monitoringRecordedAt(row.getMonitoringRecordedAt())
                .build());
        }
        return dtos;
    }

    /** Whether a physician answered for the release before the bag left, after it, or not yet. */
    private static String authorityTiming(IncompatibleReleaseDecision row) {
        if (row.getAuthoriserUserId() != null) {
            return "BEFORE_ISSUE";
        }
        return row.getCountersignedAt() != null ? "AFTER_ISSUE" : "PENDING";
    }

    // ── The setting of the hospital ─────────────────────────────────────

    public Settings settings(UUID hospitalId) {
        return protocolRepository.findByHospital_Id(hospitalId)
            .map(protocol -> new Settings(protocol.isIncompatibleFallbackEnabled(),
                IncompatibleReleaseAuthority.parseRoles(protocol.getIncompatibleFallbackDeclarerRoles()),
                protocol.getReviewDeadlineHours()))
            .orElse(Settings.NO_PROTOCOL);
    }

    public IncompatibleReleaseProtocolDTO protocol(UUID hospitalId) {
        Settings settings = settings(hospitalId);
        return new IncompatibleReleaseProtocolDTO(hospitalId, settings.fallbackEnabled(),
            orderedRoles(settings.declarerRoles()), settings.countersignDeadlineHours(),
            IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE);
    }

    private static List<String> orderedRoles(Collection<String> roles) {
        return IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE.stream().filter(roles::contains).toList();
    }

    /**
     * The laboratory director of the hospital switches the fallback on or off and says who may declare that no
     * physician can be reached. Every change is audited with its values. A hospital that never wrote its O
     * positive protocol gets its settings row here, with that protocol left inactive.
     */
    public IncompatibleReleaseProtocolDTO setProtocol(Hospital hospital, IncompatibleReleaseProtocolDTO update) {
        UUID hospitalId = hospital.getId();
        UUID caller = roleValidator.getCurrentUserId();
        if (caller == null || !assignmentRepository.existsActiveWitness(caller, hospitalId,
                IncompatibleReleaseNotices.DIRECTOR_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.incompatible.protocol.directorOnly"));
        }
        if (update.fallbackEnabled() == null) {
            // An empty body must not switch a hospital's fallback by accident.
            throw new FieldBusinessException("fallbackEnabled", "transfusion.incompatible.protocol.enabledRequired");
        }
        Set<String> roles = new LinkedHashSet<>();
        if (update.declarerRoles() == null || update.declarerRoles().isEmpty()) {
            roles.addAll(IncompatibleReleaseAuthority.DEFAULT_DECLARER_ROLES);
        } else {
            for (String role : update.declarerRoles()) {
                String code = role == null ? "" : role.trim().toUpperCase(java.util.Locale.ROOT);
                if (!IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE.contains(code)) {
                    throw new FieldBusinessException("declarerRoles", "transfusion.incompatible.protocol.roleNotAllowed");
                }
                roles.add(code);
            }
        }
        OPositiveReleaseProtocol protocol = protocolRepository.findByHospital_Id(hospitalId)
            .orElseGet(() -> OPositiveReleaseProtocol.builder().hospital(hospital).build());
        boolean enabled = Boolean.TRUE.equals(update.fallbackEnabled());
        String stored = IncompatibleReleaseAuthority.formatRoles(roles);
        protocol.setIncompatibleFallbackEnabled(enabled);
        protocol.setIncompatibleFallbackDeclarerRoles(stored);
        protocol.setUpdatedByUserId(caller);
        protocolRepository.save(protocol);
        int deadline = protocol.getReviewDeadlineHours();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.CONFIGURATION_CHANGED)
                    .status(AuditStatus.SUCCESS)
                    .userId(caller)
                    .hospitalId(hospitalId)
                    .entityType(PROTOCOL_ENTITY_TYPE)
                    .resourceId(hospitalId.toString())
                    .eventDescription("Incompatible release fallback setting changed")
                    .details(Map.of("fallbackEnabled", String.valueOf(enabled), "declarerRoles", stored))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Audit not written for the incompatible release setting of hospital {}: {}", hospitalId,
                    ex.getClass().getSimpleName());
            }
        });
        return new IncompatibleReleaseProtocolDTO(hospitalId, enabled, orderedRoles(roles), deadline,
            IncompatibleReleaseAuthority.DECLARER_ROLE_UNIVERSE);
    }
}
