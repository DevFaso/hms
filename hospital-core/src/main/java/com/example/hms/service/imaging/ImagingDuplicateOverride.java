package com.example.hms.service.imaging;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ImagingDuplicateOverrideReason;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provisional rule 2 (user decision 2026-11-15, pending the radiology
 * approver): « Commander quand même » on the imaging duplicate warning records
 * a reason from a short fixed list ({@link ImagingDuplicateOverrideReason}),
 * a note (required with OTHER), who and when, on the order and in the audit
 * trail. Mirrors the lab duplicate warning (#1150): never blocks an order,
 * records what the orderer saw and chose.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ProvisionalRule(ClinicalRuleSetKey.IMAGING_DUPLICATE_OVERRIDE)
public class ImagingDuplicateOverride {

    static final String AUDIT_ENTITY = "IMAGING_ORDER";

    private final AuditEventLogService auditEventLogService;

    /** Whether the request claims an override (the flag, or a reason sent). */
    public static boolean claimed(ImagingOrderRequestDTO request) {
        return request != null
            && (Boolean.TRUE.equals(request.getDuplicateOverride()) || request.getDuplicateOverrideReason() != null);
    }

    /**
     * A claimed override names its reason, and OTHER its note. Refused before
     * anything is written, so nothing typed is lost.
     */
    public static void requireReason(ImagingOrderRequestDTO request) {
        if (!claimed(request)) {
            return;
        }
        if (request.getDuplicateOverrideReason() == null) {
            throw new FieldBusinessException("duplicateOverrideReason", "imaging.order.duplicateOverride.reasonRequired");
        }
        if (request.getDuplicateOverrideReason() == ImagingDuplicateOverrideReason.OTHER
                && blank(request.getDuplicateOverrideNote())) {
            throw new FieldBusinessException("duplicateOverrideNote", "imaging.order.duplicateOverride.noteRequired");
        }
    }

    /**
     * Stores the override on a new order the server found a duplicate for. An
     * override claimed when the server finds none is not stored: there was
     * nothing to override.
     */
    public static void apply(ImagingOrder order, List<ImagingOrder> matches, ImagingOrderRequestDTO request,
                             UUID actorId, String actorName, LocalDateTime now) {
        if (order == null || matches == null || matches.isEmpty() || !claimed(request)) {
            return;
        }
        order.setDuplicateOverride(true);
        order.setDuplicateOverrideReason(request.getDuplicateOverrideReason());
        order.setDuplicateOverrideNote(blank(request.getDuplicateOverrideNote())
            ? null : request.getDuplicateOverrideNote().trim());
        order.setDuplicateOverrideByUserId(actorId);
        order.setDuplicateOverrideByName(actorName);
        order.setDuplicateOverrideAt(now);
    }

    /**
     * Records the placement of a flagged order on its audit trail, after
     * commit, ids and the reason code only (the note may carry PHI).
     */
    public void audit(ImagingOrder saved, List<ImagingOrder> matches) {
        if (saved == null || saved.getId() == null || matches == null || matches.isEmpty()) {
            return;
        }
        UUID orderId = saved.getId();
        UUID referenceId = matches.get(0).getId();
        UUID patientId = saved.getPatient() != null ? saved.getPatient().getId() : null;
        UUID hospitalId = saved.getHospital() != null ? saved.getHospital().getId() : null;
        UUID actorId = saved.getDuplicateOverrideByUserId() != null
            ? saved.getDuplicateOverrideByUserId() : saved.getOrderingProviderUserId();
        String description = Boolean.TRUE.equals(saved.getDuplicateOverride())
            ? "Duplicate warning overridden (" + saved.getDuplicateOverrideReason() + "): imaging order "
                + orderId + " placed while recent order " + referenceId + " of the same study exists"
            : "Duplicate warning not overridden: imaging order " + orderId + " placed while recent order "
                + referenceId + " of the same study exists";
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.IMAGING_ORDER_DUPLICATE_PLACED)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .patientId(patientId)
                    .hospitalId(hospitalId)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(orderId.toString())
                    .eventDescription(description)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Duplicate-order audit not written for imaging order {}: {}", orderId, ex.getMessage());
            }
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
