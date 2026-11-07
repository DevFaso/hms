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
import com.example.hms.model.OrderReviewHold;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.review.OrderReviewDecisionDTO;
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
public class OrderReviewHoldServiceImpl implements OrderReviewHoldService {

    static final String REFERENCE_TYPE = "ORDER_REVIEW_RUNNING";
    private static final String NOT_FOUND_KEY = "order.review.notFound";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_VERSION = "orderVersion";
    private static final String AUDIT_ENTITY = "ORDER_REVIEW";
    private static final String RUNNING_TITLE_KEY = "order.review.running.title";
    private static final String RUNNING_BODY_KEY = "order.review.running.body";

    /** Uncompleted: everything from a draft to an order still waiting on a fill or a dose. */
    private static final Set<PrescriptionStatus> UNCOMPLETED = Set.of(
        PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING_SIGNATURE, PrescriptionStatus.SIGNED,
        PrescriptionStatus.TRANSMITTED, PrescriptionStatus.TRANSMISSION_FAILED, PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.PARTIALLY_FILLED, PrescriptionStatus.PENDING_STOCK, PrescriptionStatus.SENT_TO_PARTNER,
        PrescriptionStatus.PARTNER_ACCEPTED);

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

    @Override
    public void snapshot(DeathDispute dispute) {
        UUID patient = dispute.getPatientId();
        UUID hospital = dispute.getHospitalId();
        for (Prescription order : prescriptionRepository.lockUncompletedForReview(patient, hospital, UNCOMPLETED)) {
            // A stay-ended order is already unusable, and stays so: nothing to review.
            if (!InpatientOrderEnd.endedWithItsStay(order)) {
                hold(dispute, OrderReviewType.PRESCRIPTION, order.getId(), order.getVersion(), OrderReviewStatus.HELD);
            }
        }
        for (TransfusionRequest request : requestRepository.lockOpenForPatient(patient, hospital)) {
            hold(dispute, OrderReviewType.TRANSFUSION_REQUEST, request.getId(), request.getVersion(), OrderReviewStatus.HELD);
        }
        // Running treatment is not held and is not stopped or restarted: the treating clinician assesses it.
        for (TransfusionAdministration running : administrationRepository
            .findByPatient_IdAndHospital_IdOrderByStartedAtDesc(patient, hospital)) {
            if (running.getStatus() == TransfusionAdministrationStatus.IN_PROGRESS
                    && hold(dispute, OrderReviewType.RUNNING_TRANSFUSION, running.getId(), 0L, OrderReviewStatus.ASSESSMENT_DUE)) {
                tellTheTreatingClinician(running, hospital);
            }
        }
    }

    /** One standing row per order, whichever dispute passed over it first. */
    private boolean hold(DeathDispute dispute, OrderReviewType type, UUID orderId, Long version, OrderReviewStatus status) {
        if (holdRepository.findByOrderTypeAndOrderIdAndStatusAndHospitalId(type, orderId, status, dispute.getHospitalId()).isPresent()) {
            return false;
        }
        holdRepository.save(OrderReviewHold.builder()
            .disputeId(dispute.getId())
            .hospitalId(dispute.getHospitalId())
            .patientId(dispute.getPatientId())
            .orderType(type)
            .orderId(orderId)
            .orderVersion(version != null ? version : 0L)
            .status(status)
            .build());
        return true;
    }

    private void tellTheTreatingClinician(TransfusionAdministration running, UUID hospital) {
        Set<UUID> clinicians = new LinkedHashSet<>();
        addUser(clinicians, running.getAdministeredBy());
        if (running.getRequest() != null) {
            addUser(clinicians, running.getRequest().getRequestedBy());
        }
        String unit = running.getBloodUnit() != null ? running.getBloodUnit().getUnitNumber() : null;
        Object[] bodyArgs = {unit};
        for (UUID clinician : clinicians) {
            CreateInBasketItemRequest item = new CreateInBasketItemRequest();
            item.setRecipientUserId(clinician);
            item.setHospitalId(hospital);
            item.setItemType(InBasketItemType.TASK);
            item.setPriority(InBasketPriority.URGENT);
            item.setReferenceId(running.getId());
            item.setReferenceType(REFERENCE_TYPE);
            item.setPatientId(running.getPatient() != null ? running.getPatient().getId() : null);
            item.setPatientName(running.getPatient() != null ? running.getPatient().getFullName() : null);
            item.setTitleKey(RUNNING_TITLE_KEY);
            item.setTitle(MessageUtil.resolve(NotificationLocales.STAFF, RUNNING_TITLE_KEY));
            item.setBodyKey(RUNNING_BODY_KEY);
            item.setBodyArgs(bodyArgs);
            item.setBody(MessageUtil.resolve(NotificationLocales.STAFF, RUNNING_BODY_KEY, bodyArgs));
            inBasketService.createItem(item);
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
            requireCurrentVersion(decision, current);
            requireUsable(order.getStatus() == null || !order.getStatus().isWithdrawn());
            // The same step-up as signing this order: on this call, after everything that can refuse it.
            if (controlledResolver.isControlled(order)) {
                reauthentication.requireForControlledSigning(actor, order.getId(), decision.getPassword(), decision.getMfaCode());
            }
        } else {
            TransfusionRequest order = lockRequest(hold);
            requireBloodPrescriber(actor, hold.getHospitalId());
            current = order.getVersion();
            requireCurrentVersion(decision, current);
            requireUsable(!order.isTerminal());
        }
        hold.setReviewedOrderVersion(current);
        return decide(hold, OrderReviewStatus.CONTINUED, actor, reason, "Order reconfirmed unchanged");
    }

    @Override
    public OrderReviewRowDTO replaceOrder(UUID holdId, OrderReviewDecisionDTO decision) {
        String reason = requireReason(decision);
        if (decision.getReplacementOrderId() == null) {
            throw new FieldBusinessException("replacementOrderId", "order.review.replacementRequired");
        }
        OrderReviewHold hold = loadHeld(holdId);
        UUID actor = roleValidator.getCurrentUserId();
        requireAuthorized(hold, actor);
        UUID replacement = decision.getReplacementOrderId();
        if (hold.getOrderType() == OrderReviewType.PRESCRIPTION) {
            Prescription held = lockPrescription(hold);
            requireReplacement(replacement, hold, prescriptionRepository.findById(replacement)
                .map(rx -> belongsTo(hold, rx.getPatient() != null ? rx.getPatient().getId() : null,
                    rx.getHospital() != null ? rx.getHospital().getId() : null)).orElse(false));
            hold.setReviewedOrderVersion(held.getVersion());
            prescriptionService.stopHeldOrder(held.getId(), "Replaced by order " + replacement + ": " + reason);
        } else {
            TransfusionRequest held = lockRequest(hold);
            requireReplacement(replacement, hold, requestRepository.findById(replacement)
                .map(rq -> belongsTo(hold, rq.getPatient() != null ? rq.getPatient().getId() : null,
                    rq.getHospital() != null ? rq.getHospital().getId() : null)).orElse(false));
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
            + hold.getReviewedOrderVersion() + ", dispute " + hold.getDisputeId() + ", by " + actor + ". Reason: " + reason);
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

    /** The check of signing an order, at this hospital. They need not be the original prescriber. */
    private void requirePrescriber(UUID actor, UUID hospital) {
        if (actor == null || !roleValidator.isSigningPhysician(actor, hospital)) {
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

    private static void requireCurrentVersion(OrderReviewDecisionDTO decision, Long current) {
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
            if (hold.getStatus() == OrderReviewStatus.HELD) {
                row.replacementCandidates(prescriptionRepository.findWrittenSince(hold.getPatientId(), hold.getHospitalId(),
                        hold.getCreatedAt()).stream()
                    .filter(rx -> !rx.getId().equals(hold.getOrderId()))
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
                    .filter(rq -> !rq.getId().equals(hold.getOrderId()))
                    .map(rq -> OrderReviewRowDTO.Candidate.builder().id(rq.getId())
                        .label(rq.getProductType() != null ? rq.getProductType().name() : null).build())
                    .toList());
            }
        }
        return row.build();
    }

    private void audit(OrderReviewHold hold, String description) {
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
