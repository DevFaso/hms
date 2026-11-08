package com.example.hms.service.support;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.ObstetricEligibility;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * QA D1538 review (round 40): the obstetric rule never refuses an emergency (URGENT, STAT), a clinical
 * decision still pending. So that choosing URGENT is not a silent way round the rule, every obstetric
 * order or referral created under the exemption for a patient the rule would have refused is audited
 * here ({@link AuditEventType#OBSTETRIC_RULE_SKIPPED_EMERGENCY}): the rule, the priority, the recorded
 * sex and age at the time. The caller flags the record itself ({@code obstetricRuleSkipped}) for review.
 *
 * <p>Written after commit, with ids and values computed in the transaction (no lazy association is
 * touched where there is no persistence context). A failed audit never undoes the order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ObstetricRuleSkipRecorder {

    public static final String RULE = "ObstetricEligibility";
    public static final String ENTITY_ULTRASOUND_ORDER = "ULTRASOUND_ORDER";
    public static final String ENTITY_REFERRAL = "OBGYN_REFERRAL";

    private final AuditEventLogService auditEventLogService;

    /** The details the audit row carries: the rule, why it was skipped, the priority, the record's sex and age. */
    public static Map<String, Object> details(Patient patient, String priority) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rule", RULE);
        details.put("skippedBecause", "EMERGENCY");
        details.put("priority", priority);
        details.put("recordedSex", ObstetricEligibility.isRecordedMale(patient) ? "MALE"
            : patient == null || patient.getGender() == null ? null : patient.getGender().trim().toUpperCase(java.util.Locale.ROOT));
        details.put("recordedAgeYears", ObstetricEligibility.recordedAgeYears(patient));
        details.put("recordedMale", ObstetricEligibility.isRecordedMale(patient));
        details.put("recordedChild", ObstetricEligibility.isRecordedChild(patient));
        return details;
    }

    /** Audits the skip after the order or referral is committed. */
    public void record(Patient patient, String priority, String entityType, UUID resourceId, UUID hospitalId) {
        // One fixed sentence per record kind (the portal reads each, AuditDescriptionCoverageTest);
        // the priority, sex and age are in the details.
        String description = ENTITY_REFERRAL.equals(entityType)
            ? "Obstetric rule skipped for an emergency OB/GYN referral"
            : "Obstetric rule skipped for an emergency ultrasound order";
        write(patient, priority, entityType, resourceId, hospitalId, description, details(patient, priority));
    }

    /**
     * QA D1538 review: an edit removed the reason the order was flagged (type OTHER, a patient record
     * now eligible, or no longer an emergency and judged and passing): the flag is cleared and the
     * clearing audited, under the same event, so the review trail shows both.
     */
    public void recordCleared(Patient patient, String priority, String entityType, UUID resourceId, UUID hospitalId) {
        Map<String, Object> details = details(patient, priority);
        details.put("skippedBecause", null);
        details.put("cleared", true);
        write(patient, priority, entityType, resourceId, hospitalId,
            "Obstetric rule skip cleared on an ultrasound order", details);
    }

    private void write(Patient patient, String priority, String entityType, UUID resourceId, UUID hospitalId,
                       String description, Map<String, Object> details) {
        UUID patientId = patient == null ? null : patient.getId();
        UUID actorUserId = com.example.hms.security.CallerIdentity.currentUserId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.OBSTETRIC_RULE_SKIPPED_EMERGENCY)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorUserId)
                    .patientId(patientId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .details(details)
                    .entityType(entityType)
                    .resourceId(resourceId == null ? null : resourceId.toString())
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Obstetric rule skip not audited for {} {} ({}): {}", entityType, resourceId, priority,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
