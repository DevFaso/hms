package com.example.hms.service.lab;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The lab-order duplicate warning (found while building PR #1147: one patient
 * had three identical fasting-glucose orders in the same minute, unwarned).
 * The older check needed the exact order timestamp, which the portal never
 * sends, so it never fired.
 *
 * <p>A new order is a possible duplicate when the same patient already has an
 * ACTIVE order of the same test (same definition id, or the same test code,
 * so a new version of the test still matches) at the SAME hospital, placed
 * within {@link #WINDOW}. It WARNS and never blocks: the order is placed,
 * its response carries the matches (the imaging order's shape), and the
 * placement is recorded on the order's audit trail with whether the orderer
 * acknowledged the warning ("Order anyway").
 *
 * <p>Every path that builds a new {@link LabOrder} asks {@link #find}
 * ({@code LabOrderDuplicateGuardTest}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabOrderDuplicateCheck {

    /**
     * PROVISIONAL product default, not a clinical rule (tasklist.md): how far
     * back an active order of the same test makes a new one a possible duplicate.
     */
    public static final Duration WINDOW = Duration.ofHours(24);

    /** An order still waiting for its result; a resulted, closed or voided order is not "active". */
    public static final Set<LabOrderStatus> ACTIVE = Collections.unmodifiableSet(EnumSet.of(
        LabOrderStatus.ORDERED, LabOrderStatus.PENDING, LabOrderStatus.COLLECTED,
        LabOrderStatus.RECEIVED, LabOrderStatus.IN_PROGRESS));

    /** Placeholder for "no order to exclude" ({@code o.id <> :id} never matches it). */
    private static final UUID NO_ORDER = new UUID(0L, 0L);

    private final LabOrderRepository labOrderRepository;
    private final LabOrderMapper labOrderMapper;
    private final AuditEventLogService auditEventLogService;

    /**
     * The active orders the (not yet saved, or just saved) order may duplicate,
     * newest first. Empty when the order lacks a patient, hospital or test.
     */
    public List<LabOrder> find(LabOrder order) {
        if (order == null || order.getPatient() == null || order.getPatient().getId() == null
                || order.getHospital() == null || order.getHospital().getId() == null) {
            return List.of();
        }
        LabTestDefinition definition = order.getLabTestDefinition();
        UUID definitionId = definition != null && definition.getId() != null
            ? definition.getId() : order.getLabTestDefinitionId();
        if (definitionId == null) {
            return List.of();
        }
        String testCode = definition != null ? definition.getTestCode() : null;
        return find(order.getPatient().getId(), order.getHospital().getId(), definitionId, testCode, order.getId());
    }

    /**
     * The same question before an order exists (the portal's preview): the
     * active orders of this test for this patient at this hospital, newest first.
     */
    public List<LabOrder> find(UUID patientId, UUID hospitalId, UUID definitionId, String testCode, UUID excludeOrderId) {
        if (patientId == null || hospitalId == null || definitionId == null) {
            return List.of();
        }
        String code = testCode != null ? testCode.trim().toUpperCase(java.util.Locale.ROOT) : "";
        List<LabOrder> matches = labOrderRepository.findActiveSameTestSince(
            patientId, hospitalId, definitionId, code, ACTIVE, LocalDateTime.now().minus(WINDOW),
            excludeOrderId != null ? excludeOrderId : NO_ORDER);
        return matches != null ? matches : List.of();
    }

    /**
     * Puts the warning on the response and, when there was a match, records
     * the placement on the order's audit trail after commit (ids only).
     */
    public void report(LabOrder saved, List<LabOrder> matches, boolean acknowledged,
                       LabOrderResponseDTO response, UUID actorId) {
        if (matches == null || matches.isEmpty()) {
            if (response != null) {
                response.setDuplicateOfRecentOrder(false);
                response.setDuplicateReferenceOrderId(null);
                response.setDuplicateMatches(new ArrayList<>());
                response.setDuplicateAcknowledged(null);
            }
            return;
        }
        UUID referenceId = matches.get(0).getId();
        if (response != null) {
            response.setDuplicateOfRecentOrder(true);
            response.setDuplicateReferenceOrderId(referenceId);
            response.setDuplicateMatches(new ArrayList<>(matches.stream()
                .map(labOrderMapper::toDuplicateMatchDTO).toList()));
            response.setDuplicateAcknowledged(acknowledged);
        }
        UUID orderId = saved != null ? saved.getId() : null;
        if (orderId == null) {
            return;
        }
        UUID patientId = saved.getPatient() != null ? saved.getPatient().getId() : null;
        UUID hospitalId = saved.getHospital() != null ? saved.getHospital().getId() : null;
        String description = (acknowledged
            ? "Duplicate warning acknowledged (order anyway): lab order "
            : "Duplicate warning not acknowledged: lab order ")
            + orderId + " placed while active order " + referenceId + " of the same test is open";
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.LAB_ORDER_DUPLICATE_PLACED)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .patientId(patientId)
                    .hospitalId(hospitalId)
                    .entityType("LAB_ORDER")
                    .resourceId(orderId.toString())
                    .eventDescription(description)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Duplicate-order audit not written for lab order {}: {}", orderId, ex.getMessage());
            }
        });
    }
}
