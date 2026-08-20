package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.repository.LabResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Critical-value notification loop (P0 #5).
 * <p>
 * Before this service, critical lab results were flagged and acknowledgeable
 * but nothing ever told the ordering provider — the loop that makes the flag
 * safe did not exist. Now: on result save, a critical value notifies the
 * ordering provider (in-app STOMP push, plus SMS when the IKODDI channel is
 * live); a sweep escalates results still unacknowledged after a configurable
 * delay. Acknowledgement (the existing acknowledge endpoint) is the read-back.
 * <p>
 * "Critical" matches the existing critical worklist endpoints: a persisted
 * HL7 {@link AbnormalFlag#CRITICAL}, or a computed severity of CRITICAL/HIGH.
 */
@Slf4j
@Service
public class CriticalValueNotificationService {

    private static final String NOTIFICATION_TYPE = "CRITICAL_LAB_RESULT";
    private static final String ESCALATION_TYPE = "CRITICAL_LAB_RESULT_ESCALATION";

    private final NotificationService notificationService;
    private final SmsService smsService;
    private final LabResultRepository labResultRepository;
    private final LabResultMapper labResultMapper;

    /** Minutes an unacknowledged critical result waits before escalation. */
    @Value("${hms.lab.critical-escalation.escalate-after-minutes:30}")
    private long escalateAfterMinutes;

    public CriticalValueNotificationService(
        NotificationService notificationService,
        SmsService smsService,
        LabResultRepository labResultRepository,
        LabResultMapper labResultMapper
    ) {
        this.notificationService = notificationService;
        this.smsService = smsService;
        this.labResultRepository = labResultRepository;
        this.labResultMapper = labResultMapper;
    }

    /**
     * Notify the ordering provider when a freshly saved result is critical.
     * Never propagates — a notification failure must not roll back the
     * clinical write (same policy as PatientTrackerEventPublisher).
     */
    public void notifyIfCritical(LabResult result) {
        try {
            if (result.getCriticalNotifiedAt() != null || !isCritical(result)) {
                return;
            }
            String username = resolveOrderingUsername(result);
            if (username == null) {
                // SYSTEM-actor results can arrive on orders whose staff has no
                // user account; stamp anyway so the sweep doesn't spin on them.
                log.warn("Critical lab result {} has no resolvable ordering user; skipping notification",
                    result.getId());
            } else {
                String message = buildMessage(result, false);
                notificationService.createNotification(message, username, NOTIFICATION_TYPE);
                sendSmsBestEffort(result, message);
            }
            result.setCriticalNotifiedAt(LocalDateTime.now());
            labResultRepository.save(result);
        } catch (RuntimeException ex) {
            log.warn("Critical-value notification failed for lab result {}: {}",
                result.getId(), ex.getMessage(), ex);
        }
    }

    /**
     * Escalate critical results still unacknowledged past the configured
     * delay. Each result is escalated exactly once; per-result failures are
     * logged and skipped so one bad row never stalls the sweep.
     *
     * @return number of results escalated
     */
    @Transactional
    public int escalateOverdue() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMinutes(escalateAfterMinutes));
        List<LabResult> overdue = labResultRepository.findCriticalAwaitingEscalation(cutoff);
        int escalated = 0;
        for (LabResult result : overdue) {
            try {
                String username = resolveOrderingUsername(result);
                if (username != null) {
                    String message = buildMessage(result, true);
                    notificationService.createNotification(message, username, ESCALATION_TYPE);
                    sendSmsBestEffort(result, message);
                }
                // Stamp even without a resolvable user so the sweep converges.
                result.setCriticalEscalatedAt(LocalDateTime.now());
                labResultRepository.save(result);
                escalated++;
            } catch (RuntimeException ex) {
                log.warn("Critical-value escalation failed for lab result {}: {}",
                    result.getId(), ex.getMessage(), ex);
            }
        }
        return escalated;
    }

    /** Same semantics as the /lab-results/hospital/{id}/critical endpoints. */
    private boolean isCritical(LabResult result) {
        if (result.getAbnormalFlag() == AbnormalFlag.CRITICAL) {
            return true;
        }
        LabResultResponseDTO dto = labResultMapper.toResponseDTO(result);
        String severity = dto != null ? dto.getSeverityFlag() : null;
        return "CRITICAL".equalsIgnoreCase(severity) || "HIGH".equalsIgnoreCase(severity);
    }

    private String resolveOrderingUsername(LabResult result) {
        LabOrder order = result.getLabOrder();
        Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
        User user = orderingStaff != null ? orderingStaff.getUser() : null;
        return user != null ? user.getUsername() : null;
    }

    private void sendSmsBestEffort(LabResult result, String message) {
        if (!smsService.deliversRealSms()) {
            return; // mock transport would only log — never route clinical alerts there
        }
        LabOrder order = result.getLabOrder();
        Staff orderingStaff = order != null ? order.getOrderingStaff() : null;
        User user = orderingStaff != null ? orderingStaff.getUser() : null;
        String phone = user != null ? user.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            smsService.send(phone, message);
        } catch (RuntimeException ex) {
            log.warn("Critical-value SMS failed for lab result {}: {}", result.getId(), ex.getMessage());
        }
    }

    private String buildMessage(LabResult result, boolean escalation) {
        LabOrder order = result.getLabOrder();
        String testName = order != null && order.getLabTestDefinition() != null
            ? order.getLabTestDefinition().getName() : "Lab test";
        String patientName = order != null && order.getPatient() != null
            ? order.getPatient().getFullName() : "patient";
        String value = result.getResultValue()
            + (result.getResultUnit() != null ? " " + result.getResultUnit() : "");
        String prefix = escalation
            ? "ESCALATION - unacknowledged critical lab result: "
            : "Critical lab result: ";
        return prefix + testName + " = " + value + " for " + patientName
            + ". Review and acknowledge in HMS.";
    }
}
