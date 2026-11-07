package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.OrderReviewStatus;
import com.example.hms.enums.OrderReviewType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.TransfusionAdministrationStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.DeathDispute;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.OrderReviewHold;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
import com.example.hms.payload.dto.review.OrderReviewReplacementDTO;
import com.example.hms.payload.dto.review.OrderReviewRowDTO;
import com.example.hms.repository.OrderReviewHoldRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.TransfusionAdministrationRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InBasketService;
import com.example.hms.service.InpatientOrderEnd;
import com.example.hms.service.OrderReviewHoldService;
import com.example.hms.service.PrescriberReauthenticationService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TransfusionService;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.pharmacy.PrescriptionControlledResolver;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** See {@link OrderReviewHoldService}. Each order takes its own row lock, in id order, before it is snapshotted. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderReviewHoldServiceImpl implements OrderReviewHoldService, org.springframework.beans.factory.InitializingBean {

    static final String REFERENCE_TYPE = "ORDER_REVIEW_RUNNING";
    private static final String NOT_FOUND_KEY = "order.review.notFound";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_VERSION = "orderVersion";
    private static final String AUDIT_ENTITY = "ORDER_REVIEW";
    private static final String RUNNING_TITLE_KEY = "order.review.running.title";
    private static final String RUNNING_BODY_KEY = "order.review.running.body";
    static final String HELD_REFERENCE_TYPE = "ORDER_REVIEW_HELD";
    private static final String HELD_TITLE_KEY = "order.review.notice.title";
    private static final String HELD_BODY_KEY = "order.review.notice.body";
    private static final Set<OrderReviewStatus> STANDING = Set.of(OrderReviewStatus.HELD, OrderReviewStatus.ASSESSMENT_DUE);

    /**
     * Every status an existing order can still be used from. A partner's refusal is one: that order is back in the
     * in-house queue. A finished order is asked again by {@link #stillToBeUsed}.
     */
    private static final Set<PrescriptionStatus> UNCOMPLETED = Set.of(
        PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE, PrescriptionStatus.SIGNED,
        PrescriptionStatus.TRANSMITTED, PrescriptionStatus.TRANSMISSION_FAILED, PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.PARTIALLY_FILLED, PrescriptionStatus.PENDING_STOCK, PrescriptionStatus.SENT_TO_PARTNER,
        PrescriptionStatus.PARTNER_ACCEPTED, PrescriptionStatus.PARTNER_REJECTED);

    /** Finished, unless a refill is left on it or, for a ward dispense, a dose may still be given on its open visit. */
    private static final Set<PrescriptionStatus> FINISHED = Set.of(
        PrescriptionStatus.DISPENSED, PrescriptionStatus.PARTNER_DISPENSED, PrescriptionStatus.PRINTED_FOR_PATIENT);

    private static final Set<PrescriptionStatus> LOCKED_FOR_REVIEW = java.util.stream.Stream
        .concat(UNCOMPLETED.stream(), FINISHED.stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Clock clock;
    private final OrderReviewHoldRepository holdRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final TransfusionRequestRepository requestRepository;
    private final TransfusionAdministrationRepository administrationRepository;
    private final PatientChartAccess patientChartAccess;
    private final RoleValidator roleValidator;
    private final PrescriptionControlledResolver controlledResolver;
    private final PrescriberReauthenticationService reauthentication;
    private final PrescriptionService prescriptionService;
    private final TransfusionService transfusionService;
    private final InBasketService inBasketService;
    private final AuditEventLogService auditEventLogService;
    private final OrderReviewAfterCommit afterCommit;
    private final com.example.hms.repository.DeathRecordRepository deathRecords;
    private final com.example.hms.repository.DeathDisputeRepository disputes;
    private final com.example.hms.repository.MedicationAdministrationRecordRepository doses;

    @Override
    public void afterPropertiesSet() {
        // The prescription service cannot take this bean: this one stops orders through it.
        com.example.hms.service.support.OrderReviewHoldGuard.useReview(this);
    }

    @Override
    public void snapshotAtDeath(DeathRecord record) {
        UUID patient = record.getPatient().getId();
        rebindStanding(patient, null, record.getId(), null);
        Affected affected = new Affected();
        for (Prescription order : prescriptionRepository.lockUncompletedForReview(patient, LOCKED_FOR_REVIEW)) {
            holdPrescription(order, record.getId(), null, affected);
        }
        for (TransfusionRequest request : requestRepository.lockOpenForPatient(patient)) {
            holdRequest(request, record.getId(), null, affected);
        }
        flagRunning(patient, record.getId(), null, null);
        affected.tell(patient, record.getId(), this);
    }

    @Override
    public void snapshot(DeathDispute dispute) {
        UUID patient = dispute.getPatientId();
        UUID hospital = dispute.getHospitalId();
        rebindStanding(patient, hospital, dispute.getDeathRecordId(), dispute.getId());
        Affected affected = new Affected();
        for (Prescription order : prescriptionRepository.lockUncompletedForReview(patient, LOCKED_FOR_REVIEW)) {
            if (order.getHospital() != null && hospital.equals(order.getHospital().getId())) {
                holdPrescription(order, dispute.getDeathRecordId(), dispute.getId(), affected);
            }
        }
        for (TransfusionRequest request : requestRepository.lockOpenForPatient(patient)) {
            if (request.getHospital() != null && hospital.equals(request.getHospital().getId())) {
                holdRequest(request, dispute.getDeathRecordId(), dispute.getId(), affected);
            }
        }
        flagRunning(patient, dispute.getDeathRecordId(), dispute.getId(), hospital);
        affected.tell(patient, dispute.getId(), this);
    }

    /**
     * Every event takes over the rows still standing, an order since finished or stopped included: none stays bound to
     * a voided death or a closed dispute. A dispute is its own hospital's; a death is everyone's.
     */
    private void rebindStanding(UUID patient, UUID onlyHospital, UUID recordId, UUID disputeId) {
        for (OrderReviewHold standing : holdRepository.findByPatientIdAndStatusIn(patient, STANDING)) {
            if (onlyHospital == null || onlyHospital.equals(standing.getHospitalId())) {
                standing.setDeathRecordId(recordId);
                standing.setDisputeId(disputeId);
            }
        }
    }

    @Override
    public void disputeClosed(UUID disputeId) {
        for (OrderReviewHold standing : holdRepository.findByDisputeIdAndStatusIn(disputeId, STANDING)) {
            standing.setDisputeId(null);
        }
    }

    @Override
    public void holdIfEventStands(Prescription order) {
        Event event = eventOver(order);
        if (event != null && stillToBeUsed(order)) {
            Affected affected = new Affected();
            holdPrescription(order, event.recordId(), event.disputeId(), affected);
            affected.tell(order.getPatient().getId(), event.disputeId() != null ? event.disputeId() : event.recordId(), this);
        }
    }

    @Override
    public LocalDateTime pendingSince(Prescription order) {
        OrderReviewHold standing = standingRow(order);
        if (standing != null) {
            return standing.getCreatedAt() != null ? standing.getCreatedAt() : LocalDateTime.MAX;
        }
        if (order.getStatus() != null && FINISHED.contains(order.getStatus())) {
            Event event = eventOver(order);
            return event != null ? event.at() : null;
        }
        return null;
    }

    @Override
    public void doseGivenBeforeHold(Prescription order, LocalDateTime at, String why) {
        OrderReviewHold row = standingRow(order);
        if (row == null) {
            // A finished order has no row until now: the prescriber sees this dose on the list, as any other.
            Event event = eventOver(order);
            if (event != null && order.getHospital() != null) {
                row = hold(event.recordId(), event.disputeId(), order.getHospital().getId(), order.getPatient().getId(),
                    OrderReviewType.PRESCRIPTION, order.getId(), order.getVersion(), OrderReviewStatus.HELD);
            }
        }
        if (row != null) {
            audit(row, "Dose charted as given before the hold: " + row.getOrderType() + " " + order.getId() + " at " + at
                + ", dispute " + row.getDisputeId() + ", by " + roleValidator.getCurrentUserId(), reasonDigest(why));
        }
    }

    private OrderReviewHold standingRow(Prescription order) {
        List<OrderReviewHold> rows = holdRepository.findByOrderTypeAndOrderIdAndStatus(
            OrderReviewType.PRESCRIPTION, order.getId(), OrderReviewStatus.HELD);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record Event(UUID recordId, UUID disputeId, LocalDateTime at) {
    }

    /**
     * The death event standing over an order that existed before it, unless this event's dispute already has it
     * reconfirmed. An order written after the event follows the normal workflow.
     */
    private Event eventOver(Prescription order) {
        if (order.getPatient() == null || order.getHospital() == null) {
            return null;
        }
        com.example.hms.model.DeathRecord live = deathRecords.findByPatient_Id(order.getPatient().getId()).orElse(null);
        if (live == null) {
            return null;
        }
        DeathDispute open = disputes.findOpen(live.getId(), order.getHospital().getId()).orElse(null);
        LocalDateTime at = open != null ? open.getDisputedAt() : live.getCreatedAt();
        if (at == null || order.getCreatedAt() == null || !order.getCreatedAt().isBefore(at)) {
            return null;
        }
        UUID disputeId = open != null ? open.getId() : null;
        boolean reconfirmed = holdRepository.findByOrderTypeAndOrderId(OrderReviewType.PRESCRIPTION, order.getId()).stream()
            .anyMatch(row -> row.getStatus() == OrderReviewStatus.CONTINUED && Objects.equals(row.getDisputeId(), disputeId));
        return reconfirmed ? null : new Event(live.getId(), disputeId, at);
    }

    /** Who to tell of this event's new holds, one item per hospital. Reading a clinician must never abort the event. */
    private static final class Affected {
        private final java.util.Map<UUID, Set<UUID>> readers = new java.util.LinkedHashMap<>();
        private String patientName;

        void add(UUID hospital, Staff clinician, com.example.hms.model.Patient patient) {
            Set<UUID> atHospital = readers.computeIfAbsent(hospital, h -> new LinkedHashSet<>());
            try {
                if (patientName == null && patient != null) {
                    patientName = patient.getFullName();
                }
                if (clinician != null && clinician.isActive()) {
                    addUser(atHospital, clinician);
                }
            } catch (RuntimeException ex) {
                log.warn("Could not read who to tell of a held order at hospital {}: {}", hospital, ex.getMessage());
            }
        }

        void tell(UUID patient, UUID eventId, OrderReviewHoldServiceImpl service) {
            readers.forEach((hospital, recipients) -> service.afterCommit(new OrderReviewAfterCommit.Notice(hospital, patient, patientName,
                UUID.nameUUIDFromBytes((eventId + "@" + hospital).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                HELD_REFERENCE_TYPE, HELD_TITLE_KEY, HELD_BODY_KEY, new Object[] {patientName}, recipients)));
        }
    }

    private void afterCommit(OrderReviewAfterCommit.Notice notice) {
        afterCommit(() -> afterCommit.file(notice), "notice " + notice.reference());
    }

    /** After the event has committed, and never able to undo it. */
    private static void afterCommit(Runnable action, String what) {
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.error("Order review: {} failed after commit; the holds stand: {}", what, ex.getMessage());
            }
        });
    }

    @Override
    public void reviewAgainAfterChange(OrderReviewType type, UUID orderId) {
        for (OrderReviewHold approved : holdRepository.findByOrderTypeAndOrderIdAndStatus(type, orderId, OrderReviewStatus.CONTINUED)) {
            boolean heldNow = holdRepository.existsByOrderTypeAndOrderIdAndStatus(type, orderId, OrderReviewStatus.HELD);
            if (!heldNow) {
                OrderReviewHold again = holdRepository.save(OrderReviewHold.builder()
                    .deathRecordId(approved.getDeathRecordId())
                    .disputeId(approved.getDisputeId())
                    .hospitalId(approved.getHospitalId())
                    .patientId(approved.getPatientId())
                    .orderType(type)
                    .orderId(orderId)
                    .orderVersion(currentVersion(type, orderId))
                    .status(OrderReviewStatus.HELD)
                    .build());
                audit(again, "Order held again after a change: " + again.getOrderType() + " " + orderId
                    + " changed after its reconfirmation, dispute " + again.getDisputeId());
                if (type == OrderReviewType.PRESCRIPTION) {
                    recallAfterCommit(orderId);
                }
            }
        }
    }

    private long currentVersion(OrderReviewType type, UUID orderId) {
        Long version = type == OrderReviewType.PRESCRIPTION
            ? prescriptionRepository.findById(orderId).map(Prescription::getVersion).orElse(null)
            : requestRepository.findById(orderId).map(TransfusionRequest::getVersion).orElse(null);
        return version != null ? version : 0L;
    }

    /**
     * A stay-ended order is already unusable, and stays so: nothing to review. A partner holding the offer of a newly
     * held order is told not to dispense it, or nothing here could stop it; its prescriber is told of the hold.
     */
    private void holdPrescription(Prescription order, UUID recordId, UUID disputeId, Affected affected) {
        if (order.getHospital() != null && stillToBeUsed(order) && !InpatientOrderEnd.endedWithItsStay(order)
                && hold(recordId, disputeId, order.getHospital().getId(), order.getPatient().getId(), OrderReviewType.PRESCRIPTION,
                    order.getId(), order.getVersion(), OrderReviewStatus.HELD) != null) {
            affected.add(order.getHospital().getId(), order.getStaff(), order.getPatient());
            recallAfterCommit(order.getId());
        }
    }

    /** The hold row alone already stops an accept: this only tells the partner, and a legacy row may not save. */
    private void recallAfterCommit(UUID orderId) {
        afterCommit(() -> afterCommit.recallPartnerOffers(orderId), "partner recall of order " + orderId);
    }

    /**
     * A finished order is history, unless a refill can still be released from it (never for a controlled drug, which
     * takes a new signature) or a dose can still be given: a dispense for a visit still open, not one for home.
     * A refill approved past the prescribed allowance is that prescriber's own new decision, not an existing order.
     */
    private boolean stillToBeUsed(Prescription order) {
        PrescriptionStatus status = order.getStatus();
        if (status == null || !FINISHED.contains(status)) {
            return true;
        }
        boolean refillLeft = order.getRefillsRemaining() != null && order.getRefillsRemaining() > 0 && !controlledResolver.isControlled(order);
        boolean visitOpen = order.getEncounter() == null || order.getEncounter().getStatus() == null
            || !order.getEncounter().getStatus().isClosed();
        return refillLeft || (status == PrescriptionStatus.DISPENSED && !order.isTakeHome() && visitOpen);
    }

    private void holdRequest(TransfusionRequest request, UUID recordId, UUID disputeId, Affected affected) {
        if (request.getHospital() != null
                && hold(recordId, disputeId, request.getHospital().getId(), request.getPatient().getId(),
                    OrderReviewType.TRANSFUSION_REQUEST, request.getId(), request.getVersion(), OrderReviewStatus.HELD) != null) {
            affected.add(request.getHospital().getId(), request.getRequestedBy(), request.getPatient());
        }
    }

    /** Running treatment is not held, stopped or restarted: the treating clinician records an explicit decision. */
    private void flagRunning(UUID patient, UUID recordId, UUID disputeId, UUID onlyHospital) {
        for (TransfusionAdministration running : administrationRepository.findByPatient_IdAndStatus(
                patient, TransfusionAdministrationStatus.IN_PROGRESS)) {
            UUID hospital = running.getHospital() != null ? running.getHospital().getId() : null;
            if (hospital != null && (onlyHospital == null || onlyHospital.equals(hospital))) {
                OrderReviewHold flag = hold(recordId, disputeId, hospital, patient, OrderReviewType.RUNNING_TRANSFUSION,
                    running.getId(), 0L, OrderReviewStatus.ASSESSMENT_DUE);
                if (flag != null) {
                    tellTheTreatingClinician(running, hospital, flag.getId());
                }
            }
        }
    }

    /**
     * One standing row per order: a later event binds the row it finds to its dispute, the latest one, and adds nothing.
     * Answers the row only when it wrote one.
     */
    private OrderReviewHold hold(UUID recordId, UUID disputeId, UUID hospital, UUID patient, OrderReviewType type, UUID orderId,
                                 Long version, OrderReviewStatus status) {
        OrderReviewHold standing = holdRepository.findByOrderTypeAndOrderIdAndStatusAndHospitalId(type, orderId, status, hospital)
            .orElse(null);
        if (standing != null) {
            standing.setDeathRecordId(recordId);
            standing.setDisputeId(disputeId);
            return null;
        }
        return holdRepository.save(OrderReviewHold.builder()
            .deathRecordId(recordId)
            .disputeId(disputeId)
            .hospitalId(hospital)
            .patientId(patient)
            .orderType(type)
            .orderId(orderId)
            .orderVersion(version != null ? version : 0L)
            .status(status)
            .build());
    }

    /** Filed on this flag's own row: a later dispute's flag on the same bag reaches the clinician again. */
    private void tellTheTreatingClinician(TransfusionAdministration running, UUID hospital, UUID flagId) {
        Set<UUID> clinicians = new LinkedHashSet<>();
        addIfActive(clinicians, running.getAdministeredBy());
        if (running.getRequest() != null) {
            addIfActive(clinicians, running.getRequest().getRequestedBy());
        }
        String unit = running.getBloodUnit() != null ? running.getBloodUnit().getUnitNumber() : null;
        afterCommit(new OrderReviewAfterCommit.Notice(hospital, running.getPatient() != null ? running.getPatient().getId() : null,
            running.getPatient() != null ? running.getPatient().getFullName() : null, flagId, REFERENCE_TYPE,
            RUNNING_TITLE_KEY, RUNNING_BODY_KEY, new Object[] {unit}, clinicians));
    }

    private static void addIfActive(Set<UUID> users, Staff staff) {
        if (staff != null && staff.isActive()) {
            addUser(users, staff);
        }
    }

    private static void addUser(Set<UUID> users, Staff staff) {
        if (staff != null && staff.getUser() != null) {
            users.add(staff.getUser().getId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderReviewRowDTO> forPatient(UUID patientId) {
        UUID hospital = requireHospital();
        // The role at THIS hospital: the controller's check reads the union of the caller's assignments.
        UUID actor = roleValidator.getCurrentUserId();
        if (actor == null || !roleValidator.isClinicianOrPharmacist(actor, hospital)) {
            throw new AccessDeniedException("Only a clinician or pharmacist of this hospital can read the review list.");
        }
        patientChartAccess.require(patientId, hospital);
        return holdRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, hospital).stream()
            .map(this::toRow)
            .toList();
    }

    @Override
    public OrderReviewRowDTO continueOrder(UUID holdId, OrderReviewDecisionDTO decision) {
        String reason = requireReason(decision);
        OrderReviewHold hold = loadHeld(holdId);
        UUID actor = roleValidator.getCurrentUserId();
        Long current;
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            Prescription order = lockPrescription(hold);
            requirePrescriber(actor, hold.getHospitalId());
            current = order.getVersion();
            requireBoundTo(hold, decision, current);
            requireUsable((order.getStatus() == null || !order.getStatus().isWithdrawn())
                && !InpatientOrderEnd.endedWithItsStay(order));
            // The same step-up as signing this order: on this call, after everything that can refuse it.
            if (controlledResolver.isControlled(order)) {
                reauthentication.requireForControlledSigning(actor, order.getId(), decision.getPassword(), decision.getMfaCode());
            }
        } else {
            TransfusionRequest order = lockRequest(hold);
            requireBloodPrescriber(actor, hold.getHospitalId());
            current = order.getVersion();
            requireBoundTo(hold, decision, current);
            requireUsable(!order.isTerminal());
        }
        hold.setReviewedOrderVersion(current);
        return decide(hold, OrderReviewStatus.CONTINUED, actor, reason, "Order reconfirmed unchanged");
    }

    @Override
    public OrderReviewRowDTO replaceOrder(UUID holdId, OrderReviewReplacementDTO request) {
        String reason = request == null || request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_REASON, "order.review.reasonRequired");
        }
        OrderReviewHold hold = loadHeld(holdId);
        UUID actor = roleValidator.getCurrentUserId();
        requireAuthorized(hold, actor);
        UUID replacement = request.getReplacementOrderId();
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            Prescription held = lockPrescription(hold);
            requireReplacement(replacement, hold, prescriptionRepository.findById(replacement)
                .map(rx -> belongsTo(hold, rx.getPatient() != null ? rx.getPatient().getId() : null,
                    rx.getHospital() != null ? rx.getHospital().getId() : null)
                    && replaces(hold, rx)).orElse(false));
            hold.setReviewedOrderVersion(held.getVersion());
            prescriptionService.stopHeldOrder(held.getId(), "Replaced by order " + replacement + ": " + reason);
        } else {
            TransfusionRequest held = lockRequest(hold);
            requireReplacement(replacement, hold, requestRepository.findById(replacement)
                .map(rq -> belongsTo(hold, rq.getPatient() != null ? rq.getPatient().getId() : null,
                    rq.getHospital() != null ? rq.getHospital().getId() : null)
                    && replaces(hold, rq)).orElse(false));
            hold.setReviewedOrderVersion(held.getVersion());
            transfusionService.stopHeldRequest(held.getId(), "Replaced by request " + replacement + ": " + reason);
        }
        hold.setReplacementOrderId(replacement);
        return decide(hold, OrderReviewStatus.REPLACED, actor, reason, "Order replaced by " + replacement);
    }

    @Override
    public OrderReviewRowDTO stopOrder(UUID holdId, OrderReviewDecisionDTO decision) {
        String reason = requireReason(decision);
        OrderReviewHold hold = loadHeld(holdId);
        UUID actor = roleValidator.getCurrentUserId();
        requireAuthorized(hold, actor);
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            Prescription held = lockPrescription(hold);
            hold.setReviewedOrderVersion(held.getVersion());
            if (held.getStatus() == null || !held.getStatus().isWithdrawn()) {
                prescriptionService.stopHeldOrder(held.getId(), reason);
            }
        } else {
            TransfusionRequest held = lockRequest(hold);
            hold.setReviewedOrderVersion(held.getVersion());
            if (!held.isTerminal()) {
                transfusionService.stopHeldRequest(held.getId(), reason);
            }
        }
        return decide(hold, OrderReviewStatus.STOPPED, actor, reason, "Order stopped");
    }

    @Override
    public OrderReviewRowDTO recordAssessment(UUID holdId, OrderReviewDecisionDTO decision) {
        String reason = requireReason(decision);
        OrderReviewHold hold = holdRepository.lockById(holdId)
            .filter(row -> Objects.equals(row.getHospitalId(), requireHospital()))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, holdId));
        if (hold.getStatus() != OrderReviewStatus.ASSESSMENT_DUE) {
            throw new BusinessException("order.review.decided");
        }
        UUID actor = roleValidator.getCurrentUserId();
        if (!roleValidator.isSigningPhysician(actor, hold.getHospitalId()) && !roleValidator.isMidwife(actor, hold.getHospitalId())
                && !roleValidator.isNurse(actor, hold.getHospitalId())) {
            throw new AccessDeniedException("Only a clinician of this hospital can assess a running treatment.");
        }
        return decide(hold, OrderReviewStatus.ASSESSED, actor, reason, "Running treatment assessed");
    }

    private OrderReviewRowDTO decide(OrderReviewHold hold, OrderReviewStatus outcome, UUID actor, String reason, String what) {
        hold.setStatus(outcome);
        hold.setDecidedById(actor);
        hold.setDecidedByName(currentUsername());
        hold.setDecidedAt(LocalDateTime.now(clock));
        hold.setReason(reason);
        OrderReviewHold saved = holdRepository.save(hold);
        audit(saved, what + ": " + hold.getOrderType() + " " + hold.getOrderId() + " version "
            + hold.getReviewedOrderVersion() + ", dispute " + hold.getDisputeId() + ", by " + actor, reasonDigest(reason));
        return toRow(saved);
    }

    private OrderReviewHold loadHeld(UUID holdId) {
        UUID scope = requireHospital();
        OrderReviewHold hold = holdRepository.lockById(holdId)
            .filter(row -> Objects.equals(row.getHospitalId(), scope))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, holdId));
        if (hold.getStatus() != OrderReviewStatus.HELD) {
            throw new BusinessException("order.review.decided");
        }
        return hold;
    }

    private void requireAuthorized(OrderReviewHold hold, UUID actor) {
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            requirePrescriber(actor, hold.getHospitalId());
        } else {
            requireBloodPrescriber(actor, hold.getHospitalId());
        }
    }

    /**
     * Current prescribing authority for this order at this hospital, as the sign endpoint asks it (a doctor): not merely
     * a clinical role, so a pharmacist's review grants nothing. They need not be the original prescriber.
     */
    private void requirePrescriber(UUID actor, UUID hospital) {
        if (actor == null || !roleValidator.isDoctor(actor, hospital)) {
            throw new AccessDeniedException("Only a prescriber of this hospital can review this order.");
        }
    }

    /** The roles that may request blood (TransfusionController.PRESCRIBER), at this hospital. */
    private void requireBloodPrescriber(UUID actor, UUID hospital) {
        if (actor == null || (!roleValidator.isSigningPhysician(actor, hospital) && !roleValidator.isMidwife(actor, hospital))) {
            throw new AccessDeniedException("Only a clinician who may request blood at this hospital can review this request.");
        }
    }

    private static String requireReason(OrderReviewDecisionDTO decision) {
        String reason = decision == null || decision.getReason() == null ? "" : decision.getReason().trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_REASON, "order.review.reasonRequired");
        }
        return reason;
    }

    /**
     * A reconfirmation names the dispute and the order version it read: neither may have moved since. A row bound to a
     * dispute now closed, or a page that read a dispute the row has left, reconfirms nothing.
     */
    private void requireBoundTo(OrderReviewHold hold, OrderReviewDecisionDTO decision, Long current) {
        boolean sameDispute = Objects.equals(hold.getDisputeId(), decision.getDisputeId());
        boolean stillOpen = hold.getDisputeId() == null
            || disputes.findById(hold.getDisputeId()).map(DeathDispute::isOpen).orElse(false);
        if (!sameDispute || !stillOpen) {
            throw new FieldBusinessException("disputeId", "order.review.disputeChanged");
        }
        long now = current != null ? current : 0L;
        if (decision.getOrderVersion() == null || decision.getOrderVersion() != now) {
            throw new FieldBusinessException(FIELD_VERSION, "order.review.versionChanged");
        }
    }

    private static void requireUsable(boolean usable) {
        if (!usable) {
            throw new BusinessException("order.review.notUsable");
        }
    }

    private static boolean belongsTo(OrderReviewHold hold, UUID patient, UUID hospital) {
        return Objects.equals(hold.getPatientId(), patient) && Objects.equals(hold.getHospitalId(), hospital);
    }

    /** A new order of this patient here, itself not held: a held one replaces nothing. */
    private void requireReplacement(UUID replacement, OrderReviewHold hold, boolean sameChart) {
        boolean held = holdRepository.existsByOrderTypeAndOrderIdAndStatus(hold.getOrderType(), replacement, OrderReviewStatus.HELD);
        if (!sameChart || held || replacement.equals(hold.getOrderId())) {
            throw new FieldBusinessException("replacementOrderId", "order.review.replacementInvalid");
        }
    }

    /** Written since the hold, signed and live: a draft has not been through signing, and a stopped order replaces nothing. */
    private static boolean replaces(OrderReviewHold hold, Prescription order) {
        PrescriptionStatus status = order.getStatus();
        return writtenSince(hold, order.getCreatedAt()) && status != null && !status.isWithdrawn()
            && status != PrescriptionStatus.DRAFT && status != PrescriptionStatus.PENDING_SIGNATURE
            && !InpatientOrderEnd.endedWithItsStay(order);
    }

    private static boolean replaces(OrderReviewHold hold, TransfusionRequest request) {
        return writtenSince(hold, request.getCreatedAt()) && !request.isTerminal();
    }

    private static boolean writtenSince(OrderReviewHold hold, LocalDateTime written) {
        return hold.getCreatedAt() == null || (written != null && !written.isBefore(hold.getCreatedAt()));
    }

    private Prescription lockPrescription(OrderReviewHold hold) {
        return prescriptionRepository.findByIdAndHospitalIdForUpdate(hold.getOrderId(), hold.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, hold.getId()));
    }

    private TransfusionRequest lockRequest(OrderReviewHold hold) {
        requestRepository.lockById(hold.getOrderId());
        return requestRepository.findById(hold.getOrderId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, hold.getId()));
    }

    private OrderReviewRowDTO toRow(OrderReviewHold hold) {
        OrderReviewRowDTO.OrderReviewRowDTOBuilder row = OrderReviewRowDTO.builder()
            .id(hold.getId())
            .disputeId(hold.getDisputeId())
            .orderType(hold.getOrderType())
            .orderId(hold.getOrderId())
            .orderVersion(hold.getOrderVersion())
            .status(hold.getStatus())
            .decidedByName(hold.getDecidedByName())
            .decidedAt(hold.getDecidedAt())
            .reason(hold.getReason())
            .replacementOrderId(hold.getReplacementOrderId());
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            prescriptionRepository.findById(hold.getOrderId()).ifPresent(rx -> row
                .orderLabel(rx.getMedicationName())
                .currentOrderVersion(rx.getVersion())
                .controlled(controlledResolver.isControlled(rx)));
            row.dosesGivenBeforeHold(dosesGivenBefore(hold));
            if (hold.getStatus() == OrderReviewStatus.HELD) {
                row.replacementCandidates(prescriptionRepository.findWrittenSince(hold.getPatientId(), hold.getHospitalId(),
                        hold.getCreatedAt()).stream()
                    .filter(rx -> !rx.getId().equals(hold.getOrderId()) && replaces(hold, rx))
                    .map(rx -> OrderReviewRowDTO.Candidate.builder().id(rx.getId()).label(rx.getMedicationName()).build())
                    .toList());
            }
        } else if (hold.getOrderType() == OrderReviewType.TRANSFUSION_REQUEST) {
            requestRepository.findById(hold.getOrderId()).ifPresent(rq -> row
                .orderLabel(rq.getProductType() != null ? rq.getProductType().name() : null)
                .currentOrderVersion(rq.getVersion()));
            if (hold.getStatus() == OrderReviewStatus.HELD) {
                row.replacementCandidates(requestRepository.findWrittenSince(hold.getPatientId(), hold.getHospitalId(),
                        hold.getCreatedAt()).stream()
                    .filter(rq -> !rq.getId().equals(hold.getOrderId()) && replaces(hold, rq))
                    .map(rq -> OrderReviewRowDTO.Candidate.builder().id(rq.getId())
                        .label(rq.getProductType() != null ? rq.getProductType().name() : null).build())
                    .toList());
            }
        }
        return row.build();
    }

    /** The audit row is not encrypted: it holds a digest of the reason, and the reason stays on the encrypted row. */
    private static String reasonDigest(String reason) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "{\"reasonSha256\":\"" + java.util.HexFormat.of().formatHex(digest) + "\"}";
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Given earlier, charted since: the prescriber deciding this order must see them. */
    private List<OrderReviewRowDTO.PriorDose> dosesGivenBefore(OrderReviewHold hold) {
        if (hold.getCreatedAt() == null) {
            return List.of();
        }
        return doses.findByPrescription_IdOrderByScheduledTimeDesc(hold.getOrderId()).stream()
            .filter(dose -> dose.getStatus() == com.example.hms.enums.MedicationAdministrationStatus.GIVEN
                && dose.getAdministeredAt() != null && dose.getAdministeredAt().isBefore(hold.getCreatedAt())
                && dose.getUpdatedAt() != null && !dose.getUpdatedAt().isBefore(hold.getCreatedAt()))
            .map(dose -> OrderReviewRowDTO.PriorDose.builder().administeredAt(dose.getAdministeredAt()).note(dose.getNotes()).build())
            .toList();
    }

    private void audit(OrderReviewHold hold, String description) {
        audit(hold, description, null);
    }

    private void audit(OrderReviewHold hold, String description, String details) {
        try {
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                .eventType(AuditEventType.PATIENT_UPDATE)
                .status(AuditStatus.SUCCESS)
                .entityType(AUDIT_ENTITY)
                .resourceId(String.valueOf(hold.getId()))
                .patientId(hold.getPatientId())
                .hospitalId(hold.getHospitalId())
                .userId(hold.getDecidedById())
                .userName(hold.getDecidedByName())
                .eventDescription(description)
                .details(details)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to audit order review {}: {}", hold.getId(), ex.getMessage());
        }
    }

    private UUID requireHospital() {
        UUID hospital = roleValidator.requireActiveHospitalId();
        if (hospital == null) {
            throw new BusinessException("mortality.hospitalRequired");
        }
        return hospital;
    }

    private static String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}
