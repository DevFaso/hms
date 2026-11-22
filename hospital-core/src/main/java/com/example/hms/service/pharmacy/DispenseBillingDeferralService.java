package com.example.hms.service.pharmacy;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDecisionDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralReasonDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralRequestDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.DisplayNumbers;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Policy rule 6 (provisional, owner decision 2026-11-20; V345, QA r65 D2675): « à facturer » is an exception somebody
 * else approves, not a button at the counter. Rule 6 is provisional until the hospital's director and finance manager
 * sign its credit policy.
 *
 * <p><b>The flow.</b> Somebody at the counter or a desk ASKS ({@link #request}); ANOTHER person APPROVES or rejects
 * ({@link #approve}, {@link #reject}): billing, finance or a hospital admin checks a third-party cover against its
 * claim or voucher, and finance or a hospital admin grants an outpatient credit, with a reason and an optional
 * limit. The pharmacist then EXECUTES it: the hand-over names the approved deferral ({@link #admit}). Never the same
 * person: whoever approves did not ask, and whoever hands the fill over did not approve (the V345 CHECKs hold both
 * in the database too).
 *
 * <p><b>Two paths need no approval.</b> An inpatient item goes on the account of the open stay: the gate records it
 * and nobody decides anything. In an emergency the pharmacist hands over on a logged « urgence » override, with a
 * reason: care is never held for money, and the override leaves a task for finance to review ({@link #review}).
 *
 * <p><b>Who and where.</b> Roles are checked on the assignment at the acting hospital before any lookup; every row
 * is read by its hospital, and another hospital's answers as a missing one. A support session impersonating a user
 * never asks, decides or executes. No card data: reasons and references are refused when they hold a card number.
 */
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.DEFERRED_BILLING)
public class DispenseBillingDeferralService {

    static final String NOT_FOUND = "pharmacy.deferral.notFound";
    /** The request field of a hand-over or a one-step fill that names the approved deferral. */
    public static final String FIELD_DEFERRAL = "billingDeferralId";
    /** The request field that carries the reason of an urgency override. */
    public static final String FIELD_URGENCY = "billingUrgencyReason";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_NOTE = "note";
    private static final String FIELD_LIMIT = "creditLimit";
    private static final String FIELD_COVER = "coverReference";
    private static final String AUDIT_ENTITY = "DISPENSE_BILLING_DEFERRAL";
    private static final String READERS_ONLY = "pharmacy.deferral.readersOnly";
    /** The reason recorded on a deferral closed because its order left the counter with nothing due. */
    static final String CLOSED_SETTLED_KEY = "pharmacy.deferral.closedSettled";
    private static final String APPROVERS_ONLY = "pharmacy.deferral.approversOnly";
    /** The audit entity of the fill itself, as the dispense service names it. */
    private static final String FILL_ENTITY = "DISPENSE";
    private static final int TEXT_MAX = 500;
    private static final int REFERENCE_MAX = 120;
    private static final BigDecimal LIMIT_MAX = new BigDecimal("9999999999");

    /** Asked or approved: a fill may still leave on it. One per prescription at a time. */
    static final Set<DispenseBillingDeferralStatus> OPEN_STATUSES = EnumSet.of(DispenseBillingDeferralStatus.REQUESTED,
            DispenseBillingDeferralStatus.APPROVED);

    /** A stay whose account is open: the patient is in the hospital and nothing was closed. */
    static final Set<AdmissionStatus> OPEN_STAY = EnumSet.of(AdmissionStatus.ACTIVE, AdmissionStatus.ON_LEAVE,
            AdmissionStatus.AWAITING_DISCHARGE);

    private final DispenseBillingDeferralRepository deferralRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final AdmissionRepository admissionRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;
    private final DispenseBillingDeferralFollowUp followUp;
    private final com.example.hms.service.AuditEventLogService auditEventLogService;
    private final Clock clock;

    record Actor(UUID userId, UUID hospitalId) {
    }

    /**
     * What the fills of an executed deferral still owe now. A required setter: the application always wires it; a
     * service built by hand in a unit test has none and reports no balance.
     */
    private DeferredFillBalance fillBalance;

    @org.springframework.beans.factory.annotation.Autowired
    public void setFillBalance(DeferredFillBalance fillBalance) {
        this.fillBalance = fillBalance;
    }

    /** What the counter sent with a fill that has money due: an approved deferral, or the reason of an override. */
    public record Choice(UUID deferralId, String urgencyReason) {
    }

    // ───────────────────────────── desk steps ─────────────────────────────

    /** Asks for a fill of a prescription to leave « à facturer » (REQUESTED). Decides nothing. */
    @Transactional
    public DispenseBillingDeferralDTO request(DispenseBillingDeferralRequestDTO body) {
        Actor actor = requireActor(roleValidator::canRequestDispenseBillingDeferral, "pharmacy.deferral.requestersOnly");
        DispenseBillingDeferralRequestDTO request = body != null ? body : new DispenseBillingDeferralRequestDTO();
        String reason = trimToNull(request.getReason());
        String cover = trimToNull(request.getCoverReference());
        RequiredFields.check()
                .require(request.getPrescriptionId() != null, "prescriptionId", "pharmacy.deferral.prescriptionRequired")
                .require(request.getKind() != null && request.getKind().needsApproval(), "kind",
                        "pharmacy.deferral.kindRequired")
                .require(reason != null, FIELD_REASON, "pharmacy.deferral.reasonRequired")
                .orThrow();
        requireText(reason, FIELD_REASON);
        if (cover != null) {
            requireReference(cover);
        }
        Prescription prescription = prescriptionRepository.findByIdAndHospitalIdForUpdate(request.getPrescriptionId(),
                actor.hospitalId()).orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));
        if (openStayOf(prescription, actor.hospitalId()) != null) {
            throw new FieldBusinessException("prescriptionId", "pharmacy.deferral.inpatientNotNeeded");
        }
        if (deferralRepository.existsByHospitalIdAndPrescriptionIdAndStatusIn(actor.hospitalId(),
                prescription.getId(), OPEN_STATUSES)) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.alreadyOpen"));
        }
        DispenseBillingDeferral deferral = deferralRepository.save(DispenseBillingDeferral.builder()
                .hospitalId(actor.hospitalId())
                .prescriptionId(prescription.getId())
                .patientId(prescription.getPatient().getId())
                .kind(request.getKind())
                .status(DispenseBillingDeferralStatus.REQUESTED)
                .reason(reason)
                .coverReference(cover)
                .requestedBy(actor.userId())
                .requestedAt(now())
                .build());
        audit(AuditEventType.DISPENSE_BILLING_DEFERRAL_REQUESTED, "Deferred billing of a fill requested", deferral);
        followUp.approvalNeeded(deferral, prescription.getPatient().getFullName(), prescription.getMedicationName());
        return toDto(deferral, actor, new HashMap<>());
    }

    /**
     * Another person approves a requested deferral: billing, finance or a hospital admin for a third-party cover
     * (the claim or voucher reference is recorded), finance or a hospital admin for an outpatient credit (their
     * reason, and a limit when they set one).
     */
    @Transactional
    public DispenseBillingDeferralDTO approve(UUID deferralId, DispenseBillingDeferralDecisionDTO body) {
        Actor actor = requireActor(this::approvesSomething, APPROVERS_ONLY);
        DispenseBillingDeferral deferral = lock(deferralId, actor);
        if (!approves(deferral.getKind(), actor)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(approversKey(deferral.getKind())));
        }
        requireDecidable(deferral, actor);
        DispenseBillingDeferralDecisionDTO decision = body != null ? body : new DispenseBillingDeferralDecisionDTO();
        String note = trimToNull(decision.getNote());
        String cover = trimToNull(decision.getCoverReference());
        if (cover == null) {
            cover = deferral.getCoverReference();
        }
        boolean thirdParty = deferral.getKind() == DispenseBillingDeferralKind.THIRD_PARTY_COVER;
        // A third-party cover names the claim or voucher billing checked; a credit carries the approver's reason.
        RequiredFields.check()
                .require(!thirdParty || cover != null, FIELD_COVER, "pharmacy.deferral.coverReferenceRequired")
                .require(thirdParty || note != null, FIELD_NOTE, "pharmacy.deferral.creditReasonRequired")
                .orThrow();
        if (note != null) {
            requireText(note, FIELD_NOTE);
        }
        if (thirdParty) {
            requireReference(cover);
            deferral.setCoverReference(cover);
        } else {
            deferral.setCreditLimit(requireLimit(decision.getCreditLimit()));
        }
        deferral.setStatus(DispenseBillingDeferralStatus.APPROVED);
        deferral.setDecidedBy(actor.userId());
        deferral.setDecidedAt(now());
        deferral.setDecisionNote(note);
        audit(AuditEventType.DISPENSE_BILLING_DEFERRAL_APPROVED, "Deferred billing of a fill approved", deferral);
        followUp.close(deferral);
        return toDto(deferral, actor, new HashMap<>());
    }

    /** Another person rejects a requested deferral, with the reason. */
    @Transactional
    public DispenseBillingDeferralDTO reject(UUID deferralId, DispenseBillingDeferralReasonDTO body) {
        Actor actor = requireActor(this::approvesSomething, APPROVERS_ONLY);
        DispenseBillingDeferral deferral = lock(deferralId, actor);
        if (!approves(deferral.getKind(), actor)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(approversKey(deferral.getKind())));
        }
        requireDecidable(deferral, actor);
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check().require(reason != null, FIELD_REASON, "pharmacy.deferral.rejectReasonRequired").orThrow();
        requireText(reason, FIELD_REASON);
        deferral.setStatus(DispenseBillingDeferralStatus.REJECTED);
        deferral.setDecidedBy(actor.userId());
        deferral.setDecidedAt(now());
        deferral.setDecisionNote(reason);
        audit(AuditEventType.DISPENSE_BILLING_DEFERRAL_REJECTED, "Deferred billing of a fill rejected", deferral);
        followUp.close(deferral);
        return toDto(deferral, actor, new HashMap<>());
    }

    /**
     * Withdraws a deferral nobody handed a fill over on yet, with the reason: whoever asked for it, or an approver
     * of its kind.
     */
    @Transactional
    public DispenseBillingDeferralDTO cancel(UUID deferralId, DispenseBillingDeferralReasonDTO body) {
        Actor actor = requireActor((user, hospital) -> roleValidator.canRequestDispenseBillingDeferral(user, hospital)
                || approvesSomething(user, hospital), "pharmacy.deferral.requestersOnly");
        DispenseBillingDeferral deferral = lock(deferralId, actor);
        if (!OPEN_STATUSES.contains(deferral.getStatus())) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.notCancellable"));
        }
        if (!actor.userId().equals(deferral.getRequestedBy()) && !approves(deferral.getKind(), actor)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("pharmacy.deferral.cancelOwnOnly"));
        }
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check().require(reason != null, FIELD_REASON, "pharmacy.deferral.cancelReasonRequired").orThrow();
        requireText(reason, FIELD_REASON);
        deferral.setStatus(DispenseBillingDeferralStatus.CANCELLED);
        deferral.setCancelledBy(actor.userId());
        deferral.setCancelledAt(now());
        deferral.setCancelReason(reason);
        audit(AuditEventType.DISPENSE_BILLING_DEFERRAL_CANCELLED, "Deferred billing of a fill withdrawn", deferral);
        followUp.close(deferral);
        return toDto(deferral, actor, new HashMap<>());
    }

    /** Finance reviews an urgency override afterwards, with a note: never the pharmacist who made it. */
    @Transactional
    public DispenseBillingDeferralDTO review(UUID deferralId, DispenseBillingDeferralReasonDTO body) {
        Actor actor = requireActor(roleValidator::canReviewDispenseBillingOverride, "pharmacy.deferral.reviewersOnly");
        DispenseBillingDeferral deferral = lock(deferralId, actor);
        if (deferral.getKind() != DispenseBillingDeferralKind.URGENCY_OVERRIDE) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.notAnOverride"));
        }
        if (deferral.getReviewedAt() != null) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.alreadyReviewed"));
        }
        if (actor.userId().equals(deferral.getRequestedBy())) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.selfReview"));
        }
        String note = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check().require(note != null, FIELD_REASON, "pharmacy.deferral.reviewNoteRequired").orThrow();
        requireText(note, FIELD_REASON);
        deferral.setReviewedBy(actor.userId());
        deferral.setReviewedAt(now());
        deferral.setReviewNote(note);
        audit(AuditEventType.DISPENSE_BILLING_OVERRIDE_REVIEWED, "Urgency override of a fill's billing reviewed",
                deferral);
        followUp.close(deferral);
        return toDto(deferral, actor, new HashMap<>());
    }

    // ───────────────────────────── reads ─────────────────────────────

    /**
     * The work to do at the acting hospital: the deferrals asked for or approved, and the urgency overrides not yet
     * reviewed. With {@code statuses}, the deferrals in those states instead; with {@code prescriptionId}, only
     * those of that prescription (what the counter reads before a hand-over). With {@code stillDueOnly}, only the
     * deferrals whose fills still owe money now (what finance chases): a fill since paid, covered by a claim a
     * money desk stands behind, or cancelled, owes nothing.
     */
    @Transactional(readOnly = true)
    public List<DispenseBillingDeferralDTO> list(Collection<DispenseBillingDeferralStatus> statuses,
                                                 UUID prescriptionId, boolean stillDueOnly) {
        Actor actor = requireReader();
        boolean workList = statuses == null || statuses.isEmpty();
        Collection<DispenseBillingDeferralStatus> wanted = workList ? OPEN_STATUSES : statuses;
        List<DispenseBillingDeferral> rows = new ArrayList<>(prescriptionId != null
                ? deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(
                        actor.hospitalId(), prescriptionId, wanted)
                : deferralRepository.findByHospitalIdAndStatusInOrderByRequestedAtAsc(actor.hospitalId(), wanted));
        if (workList && prescriptionId == null) {
            rows.addAll(deferralRepository.findByHospitalIdAndKindAndReviewedAtIsNullOrderByRequestedAtAsc(
                    actor.hospitalId(), DispenseBillingDeferralKind.URGENCY_OVERRIDE));
        }
        Map<UUID, String> names = new HashMap<>();
        return rows.stream().map(row -> toDto(row, actor, names))
                .filter(dto -> !stillDueOnly || (dto.getStillDue() != null && dto.getStillDue().signum() > 0))
                .toList();
    }

    /**
     * What the counter reads before a fill of this prescription leaves with money due: whether it is an inpatient
     * item (the account of the open stay, nothing to approve) and the open deferrals of the prescription. A
     * prescription of another hospital answers as a missing one.
     */
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.pharmacy.DispenseBillingCounterDTO counter(UUID prescriptionId) {
        Actor actor = requireCounterReader();
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .filter(p -> p.getHospital() != null && actor.hospitalId().equals(p.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));
        Map<UUID, String> names = new HashMap<>();
        return com.example.hms.payload.dto.pharmacy.DispenseBillingCounterDTO.builder()
                .onOpenStay(openStayOf(prescription, actor.hospitalId()) != null)
                .open(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(
                        actor.hospitalId(), prescription.getId(), OPEN_STATUSES).stream()
                        .map(row -> toDto(row, actor, names)).toList())
                .build();
    }

    /**
     * These deferrals at {@code hospitalId}, by id: what the reconciliation of a day lists behind each fill that
     * names one. The caller has already been admitted as a reader of that hospital's reconciliation.
     */
    @Transactional(readOnly = true)
    public Map<UUID, DispenseBillingDeferralDTO> byIds(UUID hospitalId, Collection<UUID> deferralIds) {
        Map<UUID, DispenseBillingDeferralDTO> byId = new HashMap<>();
        if (hospitalId == null || deferralIds == null || deferralIds.isEmpty()) {
            return byId;
        }
        Map<UUID, String> names = new HashMap<>();
        for (DispenseBillingDeferral row : deferralRepository.findByHospitalIdAndIdIn(hospitalId, deferralIds)) {
            byId.put(row.getId(), toDto(row, null, names));
        }
        return byId;
    }

    /** The override the same person already made today for this prescription with the same reason, not yet reviewed. */
    private DispenseBillingDeferral overrideOfThisHandOver(Prescription prescription, UUID hospitalId, UUID actorId,
                                                           String urgency, LocalDateTime now) {
        return deferralRepository
                .findFirstByHospitalIdAndPrescriptionIdAndKindAndRequestedByAndReviewedAtIsNullOrderByRequestedAtDesc(
                        hospitalId, prescription.getId(), DispenseBillingDeferralKind.URGENCY_OVERRIDE, actorId)
                .filter(row -> row.getRequestedAt() != null
                        && row.getRequestedAt().toLocalDate().equals(now.toLocalDate())
                        && urgency.equals(row.getReason()))
                .orElse(null);
    }

    // ───────────────────────────── the counter's gate ─────────────────────────────

    /**
     * The one gate of a fill that leaves the pharmacy with {@code due} unpaid. Returns the deferral it leaves on,
     * already written, or refuses the fill. In this order: an inpatient item goes on the account of the open stay;
     * an approved deferral of this prescription is executed, by somebody other than its approver and within its
     * limit; a logged urgency override, with a reason, by a pharmacist or a hospital admin; otherwise the fill is
     * refused as unpaid. The caller stamps the fill with {@link #stamp} and, once the fill has an id, reports it
     * with {@link #executed}.
     */
    @Transactional
    public DispenseBillingDeferral admit(Prescription prescription, UUID hospitalId, BigDecimal due, Choice choice,
                                         User actor) {
        return admitFill(prescription, hospitalId, due, choice, actor, FillClaimCover.Gap.NONE);
    }

    /**
     * {@link #admit(Prescription, UUID, BigDecimal, Choice, User)} for a fill a claim was sent for (QA D2931,
     * r66): when the counter sent no basis, a fill whose claim does not count for the person handing over is told
     * why ({@code claimGap}), not that it « is not paid ».
     */
    @Transactional
    public DispenseBillingDeferral admit(Prescription prescription, UUID hospitalId, BigDecimal due, Choice choice,
                                         User actor, FillClaimCover.Gap claimGap) {
        return admitFill(prescription, hospitalId, due, choice, actor, claimGap);
    }

    /** The gate itself; both public entries bring the transaction. */
    private DispenseBillingDeferral admitFill(Prescription prescription, UUID hospitalId, BigDecimal due,
                                              Choice choice, User actor, FillClaimCover.Gap claimGap) {
        UUID actorId = actor != null ? actor.getId() : null;
        if (prescription == null || hospitalId == null || actorId == null) {
            throw unpaid(FillClaimCover.Gap.NONE, due);
        }
        LocalDateTime now = now();
        Admission stay = openStayOf(prescription, hospitalId);
        if (stay != null) {
            return deferralRepository.save(DispenseBillingDeferral.builder()
                    .hospitalId(hospitalId)
                    .prescriptionId(prescription.getId())
                    .patientId(prescription.getPatient().getId())
                    .admissionId(stay.getId())
                    .kind(DispenseBillingDeferralKind.ADMISSION_ACCOUNT)
                    .status(DispenseBillingDeferralStatus.EXECUTED)
                    .requestedBy(actorId)
                    .requestedAt(now)
                    .executedBy(actorId)
                    .executedAt(now)
                    .amountDue(due)
                    .build());
        }
        UUID deferralId = choice != null ? choice.deferralId() : null;
        String urgency = trimToNull(choice != null ? choice.urgencyReason() : null);
        if (deferralId != null && urgency != null) {
            throw new FieldBusinessException(FIELD_URGENCY, "pharmacy.handOver.deferBothGiven");
        }
        if (deferralId == null && urgency == null) {
            throw unpaid(claimGap, due);
        }
        com.example.hms.security.context.ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService,
                "deferred billing of a fill");
        if (deferralId != null) {
            return execute(deferralId, prescription, hospitalId, due, actorId, now);
        }
        return overrideForUrgency(prescription, hospitalId, due, actorId, urgency, now);
    }

    /**
     * The refusal of a fill that would leave with {@code due} unpaid and no basis. QA D2931 (r66): a fill whose
     * claim covers it for anybody but the person handing over (they stand behind it alone), or for nobody yet (no
     * money desk recorded it), is told that and what to do next; any other fill is « not paid ».
     */
    public static FieldBusinessException unpaid(FillClaimCover.Gap claimGap, BigDecimal due) {
        String amount = DisplayNumbers.quantity(due);
        if (claimGap == FillClaimCover.Gap.OWN_VOUCHER) {
            return new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.claimVouchedBySelf", amount);
        }
        if (claimGap == FillClaimCover.Gap.NO_VOUCHER) {
            return new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.claimNotVouched", amount);
        }
        return new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.unpaid", amount);
    }

    /**
     * The logged urgency override: a pharmacist or a hospital admin, with a reason; reviewed afterwards by
     * somebody else. A further lot of the same hand-over goes on the override already made.
     */
    private DispenseBillingDeferral overrideForUrgency(Prescription prescription, UUID hospitalId, BigDecimal due,
                                                       UUID actorId, String urgency, LocalDateTime now) {
        if (!roleValidator.canOverrideDispenseBillingForUrgency(actorId, hospitalId)) {
            throw new FieldBusinessException(FIELD_URGENCY, "pharmacy.handOver.urgencyRoleRequired");
        }
        if (urgency.length() > TEXT_MAX) {
            throw new FieldBusinessException(FIELD_URGENCY, "pharmacy.handOver.urgencyReasonTooLong");
        }
        CardNumbers.requireNone(urgency, FIELD_URGENCY);
        // A fill the counter splits over several lots is ONE hand-over: its further lots go on the override the
        // same person already made today for this prescription, with the same reason. One override, one review.
        DispenseBillingDeferral sameHandOver = overrideOfThisHandOver(prescription, hospitalId, actorId, urgency, now);
        if (sameHandOver != null) {
            sameHandOver.setAmountDue(sameHandOver.getAmountDue() == null ? due : sameHandOver.getAmountDue().add(due));
            return sameHandOver;
        }
        return deferralRepository.save(DispenseBillingDeferral.builder()
                .hospitalId(hospitalId)
                .prescriptionId(prescription.getId())
                .patientId(prescription.getPatient().getId())
                .kind(DispenseBillingDeferralKind.URGENCY_OVERRIDE)
                .status(DispenseBillingDeferralStatus.EXECUTED)
                .reason(urgency)
                .requestedBy(actorId)
                .requestedAt(now)
                .executedBy(actorId)
                .executedAt(now)
                .amountDue(due)
                .build());
    }

    /**
     * The approved deferral a fill leaves on: of this prescription, not approved by whoever hands over, within its
     * limit. One approval covers one hand-over; a fill the counter splits over two lots is two rows of that one
     * hand-over, so the same person may go on with it the same day, and the limit holds for their total.
     */
    private DispenseBillingDeferral execute(UUID deferralId, Prescription prescription, UUID hospitalId,
                                            BigDecimal due, UUID actorId, LocalDateTime now) {
        DispenseBillingDeferral deferral = deferralRepository.findLockedByIdAndHospitalId(deferralId, hospitalId)
                .orElseThrow(() -> new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.deferralNotApproved"));
        boolean first = deferral.getStatus() == DispenseBillingDeferralStatus.APPROVED;
        boolean sameHandOver = deferral.getStatus() == DispenseBillingDeferralStatus.EXECUTED
                && actorId.equals(deferral.getExecutedBy()) && deferral.getExecutedAt() != null
                && deferral.getExecutedAt().toLocalDate().equals(now.toLocalDate());
        if (!(first || sameHandOver) || !deferral.getKind().needsApproval() || deferral.getDecidedBy() == null
                || !prescription.getId().equals(deferral.getPrescriptionId())) {
            throw new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.deferralNotApproved");
        }
        if (actorId.equals(deferral.getDecidedBy())) {
            throw new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.deferralApproverHandsOver");
        }
        BigDecimal total = first || deferral.getAmountDue() == null ? due : deferral.getAmountDue().add(due);
        if (deferral.getCreditLimit() != null && total.compareTo(deferral.getCreditLimit()) > 0) {
            throw new FieldBusinessException(FIELD_DEFERRAL, "pharmacy.handOver.deferralOverLimit",
                    DisplayNumbers.quantity(total), DisplayNumbers.quantity(deferral.getCreditLimit()));
        }
        deferral.setStatus(DispenseBillingDeferralStatus.EXECUTED);
        deferral.setExecutedBy(actorId);
        if (first) {
            deferral.setExecutedAt(now);
        }
        deferral.setAmountDue(total);
        return deferral;
    }

    /**
     * Marks the fill as left on {@code deferral}. The ONLY writer of a fill's « à facturer » columns
     * ({@code DeferredBillingGuardTest}): a fill cannot be marked deferred without a deferral row behind it.
     */
    public void stamp(Dispense dispense, DispenseBillingDeferral deferral, UUID actorId, LocalDateTime at,
                      BigDecimal due) {
        dispense.setBillingDeferralId(deferral.getId());
        dispense.setBillingDeferredReason(deferral.getReason());
        dispense.setBillingDeferredBy(actorId);
        dispense.setBillingDeferredAt(at);
        dispense.setBillingDeferredAmount(due);
    }

    /**
     * A fill that left on a deferral was cancelled: the stock is back, so the fill owes nothing, and its deferral
     * gives back exactly what that fill had left owing. The row stays (it is the record of what was decided).
     */
    @Transactional
    public void fillCancelled(Dispense fill) {
        if (fill == null || fill.getBillingDeferralId() == null || fill.getBillingDeferredAmount() == null
                || fill.getPharmacy() == null || fill.getPharmacy().getHospital() == null) {
            return;
        }
        deferralRepository.findLockedByIdAndHospitalId(fill.getBillingDeferralId(),
                fill.getPharmacy().getHospital().getId()).ifPresent(deferral -> {
                    BigDecimal owed = deferral.getAmountDue() != null ? deferral.getAmountDue() : BigDecimal.ZERO;
                    deferral.setAmountDue(owed.subtract(fill.getBillingDeferredAmount()).max(BigDecimal.ZERO));
                });
        fill.setBillingDeferredAmount(BigDecimal.ZERO);
    }

    /**
     * QA D2929 (r66): the order is fully dispensed and its last fill left the counter owing nothing (paid at the
     * checkout, or covered by a claim a money desk stands behind). A deferral still asked for or approved for it
     * has nothing left to cover: it stayed in everybody's « À traiter » list, and an approved one stayed usable.
     * Each is withdrawn here, by the hand-over, with the reason recorded; its task and its bell are closed. The
     * row stays (it is the record of what was asked and decided). An order with more to dispense keeps its
     * deferral: its next fill may still leave on it.
     */
    @Transactional
    public void closeOpenOnSettledOrder(Prescription prescription, UUID hospitalId, UUID actorId) {
        if (prescription == null || prescription.getId() == null || hospitalId == null || actorId == null) {
            return;
        }
        List<DispenseBillingDeferral> open = deferralRepository
                .findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(hospitalId, prescription.getId(),
                        OPEN_STATUSES);
        for (DispenseBillingDeferral listed : open) {
            DispenseBillingDeferral deferral = deferralRepository
                    .findLockedByIdAndHospitalId(listed.getId(), hospitalId).orElse(null);
            if (deferral == null || !OPEN_STATUSES.contains(deferral.getStatus())) {
                continue;
            }
            deferral.setStatus(DispenseBillingDeferralStatus.CANCELLED);
            deferral.setCancelledBy(actorId);
            deferral.setCancelledAt(now());
            deferral.setCancelReason(MessageUtil.resolve(com.example.hms.service.i18n.NotificationLocales.STAFF,
                    CLOSED_SETTLED_KEY));
            audit(AuditEventType.DISPENSE_BILLING_DEFERRAL_CANCELLED,
                    "Deferred billing of a fill closed: the order left the counter with nothing due", deferral);
            followUp.close(deferral);
        }
    }

    /** The fill now has an id: the deferral names it, the audit row is written and the follow-up task is filed. */
    @Transactional
    public void executed(DispenseBillingDeferral deferral, Dispense dispense, Prescription prescription) {
        // The first fill names the row; a further lot of the same hand-over only adds its audit line.
        boolean first = deferral.getDispenseId() == null;
        if (first) {
            deferral.setDispenseId(dispense.getId());
        }
        String fillId = dispense.getId().toString();
        switch (deferral.getKind()) {
            case ADMISSION_ACCOUNT -> support.logAudit(AuditEventType.DISPENSE_POSTED_TO_ADMISSION_ACCOUNT,
                    "Fill charged to the account of the open stay", fillId, FILL_ENTITY, deferral.getHospitalId());
            case URGENCY_OVERRIDE -> {
                support.logAudit(AuditEventType.DISPENSE_BILLING_URGENCY_OVERRIDE,
                        "Fill handed over unpaid on an urgency override", fillId, FILL_ENTITY,
                        deferral.getHospitalId());
                if (first) {
                    followUp.reviewNeeded(deferral, prescription.getPatient() != null
                            ? prescription.getPatient().getFullName() : null, dispense.getMedicationName());
                }
            }
            default -> support.logAudit(AuditEventType.DISPENSE_BILLING_DEFERRED,
                    "Fill handed over unpaid, marked to be billed", fillId, FILL_ENTITY, deferral.getHospitalId());
        }
    }

    /** Whether a fill of this order is an inpatient item, charged to the account of its open stay. */
    @Transactional(readOnly = true)
    public boolean chargedToOpenStay(Prescription prescription, UUID hospitalId) {
        return prescription != null && hospitalId != null && openStayOf(prescription, hospitalId) != null;
    }

    // ───────────────────────────── internals ─────────────────────────────

    /**
     * The open stay an order is charged to, or null. An order written during a stay (its visit names the admission),
     * not a take-home prescription, while that stay is still open at this hospital.
     */
    private Admission openStayOf(Prescription prescription, UUID hospitalId) {
        if (prescription.isTakeHome()) {
            return null;
        }
        Encounter encounter = prescription.getEncounter();
        UUID admissionId = encounter != null ? encounter.getAdmissionId() : null;
        if (admissionId != null) {
            // The visit names its stay: that stay or none (never another one of the patient's).
            return admissionRepository.findById(admissionId)
                    .filter(stay -> stay.getHospital() != null && hospitalId.equals(stay.getHospital().getId()))
                    .filter(stay -> OPEN_STAY.contains(stay.getStatus()))
                    .orElse(null);
        }
        return openStayOfPatient(prescription, hospitalId);
    }

    /**
     * QA D3404 (r70): an inpatient order written without the stay's visit (the visit is optional in the prescribing
     * form) was refused at hand-over as unpaid although the patient lay in a bed. Such an order belongs to the
     * patient's open stay at this hospital when it is an order of that stay by the rule the ward uses
     * ({@link com.example.hms.service.InpatientOrderEnd#orderOfStay}): no visit or an inpatient visit, written since
     * the admission, or the emergency visit that led to it. An outpatient visit's order is never the stay's, a stay
     * that has ended is never charged, and an order written before the admission stays an outpatient one.
     */
    private Admission openStayOfPatient(Prescription prescription, UUID hospitalId) {
        if (prescription.getPatient() == null || prescription.getPatient().getId() == null) {
            return null;
        }
        List<Admission> open = admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
                prescription.getPatient().getId(), hospitalId, OPEN_STAY);
        if (open == null) {
            return null;
        }
        return open.stream()
                .filter(stay -> stay.getHospital() != null && hospitalId.equals(stay.getHospital().getId()))
                .filter(stay -> OPEN_STAY.contains(stay.getStatus()))
                .filter(stay -> com.example.hms.service.InpatientOrderEnd.orderOfStay(prescription, stay))
                .max(java.util.Comparator.comparing(Admission::getAdmissionDateTime,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    private boolean approvesSomething(UUID userId, UUID hospitalId) {
        return roleValidator.canApproveThirdPartyCover(userId, hospitalId)
                || roleValidator.canApproveOutpatientCredit(userId, hospitalId);
    }

    /** Whether the actor approves deferrals of this kind: never the pharmacist, and credit never billing alone. */
    private boolean approves(DispenseBillingDeferralKind kind, Actor actor) {
        return switch (kind) {
            case THIRD_PARTY_COVER -> roleValidator.canApproveThirdPartyCover(actor.userId(), actor.hospitalId());
            case OUTPATIENT_CREDIT -> roleValidator.canApproveOutpatientCredit(actor.userId(), actor.hospitalId());
            default -> false;
        };
    }

    /** Who decides a deferral of this kind, said to whoever is not one of them. */
    private static String approversKey(DispenseBillingDeferralKind kind) {
        return kind == DispenseBillingDeferralKind.OUTPATIENT_CREDIT
                ? "pharmacy.deferral.creditApproversOnly" : APPROVERS_ONLY;
    }

    /** Still waiting for its decision, and never decided by whoever asked for it. */
    private static void requireDecidable(DispenseBillingDeferral deferral, Actor actor) {
        if (deferral.getStatus() != DispenseBillingDeferralStatus.REQUESTED) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.notRequested"));
        }
        if (actor.userId().equals(deferral.getRequestedBy())) {
            throw new ConflictException(MessageUtil.resolve("pharmacy.deferral.selfApproval"));
        }
    }

    private Actor requireActor(BiPredicate<UUID, UUID> holdsRole, String refusalKey) {
        com.example.hms.security.context.ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService,
                "deferred billing of a fill");
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null || !holdsRole.test(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(refusalKey));
        }
        return new Actor(userId, hospitalId);
    }

    private Actor requireReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null
                || !(roleValidator.canRequestDispenseBillingDeferral(userId, hospitalId)
                    || approvesSomething(userId, hospitalId)
                    || roleValidator.canReviewDispenseBillingOverride(userId, hospitalId))) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(READERS_ONLY));
        }
        return new Actor(userId, hospitalId);
    }

    /**
     * Whoever hands fills over reads what one may leave on: the readers of the list, and the pharmacy verifier
     * (who may execute an approved deferral at a hand-over, and reads nothing else here).
     */
    private Actor requireCounterReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID userId = roleValidator.getCurrentUserId();
        if (hospitalId == null || userId == null
                || !roleValidator.canReadDispenseBillingCounter(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(READERS_ONLY));
        }
        return new Actor(userId, hospitalId);
    }

    private DispenseBillingDeferral lock(UUID deferralId, Actor actor) {
        return deferralRepository.findLockedByIdAndHospitalId(deferralId, actor.hospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private static void requireText(String text, String field) {
        if (text.length() > TEXT_MAX) {
            throw new FieldBusinessException(field, "pharmacy.deferral.reasonTooLong");
        }
        CardNumbers.requireNone(text, field);
    }

    private static void requireReference(String reference) {
        if (reference.length() > REFERENCE_MAX) {
            throw new FieldBusinessException(FIELD_COVER, "pharmacy.deferral.coverReferenceTooLong");
        }
        CardNumbers.requireNone(reference, FIELD_COVER);
    }

    /** An optional ceiling: whole francs, above zero. */
    private static BigDecimal requireLimit(BigDecimal limit) {
        if (limit == null) {
            return null;
        }
        if (limit.signum() <= 0 || limit.stripTrailingZeros().scale() > 0 || limit.compareTo(LIMIT_MAX) > 0) {
            throw new FieldBusinessException(FIELD_LIMIT, "pharmacy.deferral.limitInvalid");
        }
        return limit;
    }

    private void audit(AuditEventType type, String description, DispenseBillingDeferral deferral) {
        // QA D2924 (r66): the deferral's own hospital anchors the row for the hospital admin's audit log.
        support.logAudit(type, description, deferral.getId().toString(), AUDIT_ENTITY, deferral.getHospitalId());
    }

    private DispenseBillingDeferralDTO toDto(DispenseBillingDeferral d, Actor actor, Map<UUID, String> names) {
        Prescription prescription = prescriptionRepository.findById(d.getPrescriptionId())
                .filter(p -> p.getHospital() != null && d.getHospitalId().equals(p.getHospital().getId()))
                .orElse(null);
        boolean canDecide = actor != null && d.getStatus() == DispenseBillingDeferralStatus.REQUESTED
                && !actor.userId().equals(d.getRequestedBy()) && approves(d.getKind(), actor);
        boolean canReview = actor != null && d.getKind() == DispenseBillingDeferralKind.URGENCY_OVERRIDE
                && d.getReviewedAt() == null && !actor.userId().equals(d.getRequestedBy())
                && roleValidator.canReviewDispenseBillingOverride(actor.userId(), actor.hospitalId());
        boolean canExecute = actor != null && d.getStatus() == DispenseBillingDeferralStatus.APPROVED
                && !actor.userId().equals(d.getDecidedBy());
        return DispenseBillingDeferralDTO.builder()
                .id(d.getId())
                .prescriptionId(d.getPrescriptionId())
                .patientId(d.getPatientId())
                .patientName(prescription != null && prescription.getPatient() != null
                        ? prescription.getPatient().getFullName() : null)
                .medicationName(prescription != null ? prescription.getMedicationName() : null)
                .dispenseId(d.getDispenseId())
                .admissionId(d.getAdmissionId())
                .kind(d.getKind().name())
                .status(d.getStatus().name())
                .reason(d.getReason())
                .requestedBy(d.getRequestedBy())
                .requestedByName(nameOf(d.getRequestedBy(), names))
                .requestedAt(d.getRequestedAt())
                .decidedBy(d.getDecidedBy())
                .decidedByName(nameOf(d.getDecidedBy(), names))
                .decidedAt(d.getDecidedAt())
                .decisionNote(d.getDecisionNote())
                .creditLimit(d.getCreditLimit())
                .coverReference(d.getCoverReference())
                .executedBy(d.getExecutedBy())
                .executedByName(nameOf(d.getExecutedBy(), names))
                .executedAt(d.getExecutedAt())
                .amountDue(d.getAmountDue())
                .reviewedBy(d.getReviewedBy())
                .reviewedByName(nameOf(d.getReviewedBy(), names))
                .reviewedAt(d.getReviewedAt())
                .reviewNote(d.getReviewNote())
                .cancelledByName(nameOf(d.getCancelledBy(), names))
                .cancelledAt(d.getCancelledAt())
                .cancelReason(d.getCancelReason())
                .patientMrn(prescription != null && prescription.getPatient() != null
                        ? prescription.getPatient().getMrnForHospital(d.getHospitalId()) : null)
                .stillDue(fillBalance != null && d.getStatus() == DispenseBillingDeferralStatus.EXECUTED
                        ? fillBalance.stillDue(d) : null)
                .canDecide(canDecide)
                .canReview(canReview)
                .canExecute(canExecute)
                .build();
    }

    /** A staff member's name as shown on the list; read once per call. */
    String nameOf(UUID userId, Map<UUID, String> names) {
        if (userId == null) {
            return null;
        }
        return names.computeIfAbsent(userId, id -> userRepository.findById(id)
                .map(u -> ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                        + (u.getLastName() != null ? u.getLastName() : "")).trim())
                .orElse(""));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
