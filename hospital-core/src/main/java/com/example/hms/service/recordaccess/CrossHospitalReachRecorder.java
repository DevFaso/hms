package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * E8 #53 / E9 — accounts for the REACH of a cross-hospital read, not just the
 * access: one {@code RECORD_SHARE} row per foreign source hospital naming the
 * actor, the hospital they acted in, the hospital whose rows were surfaced and
 * how many. The patient's disclosure report reads from these rows.
 *
 * <p>Extracted from {@code PatientServiceImpl} in E9 #59 so every surface that
 * now returns foreign rows — diagnoses, nursing notes, chart updates, advance
 * directives, the doctor record — records reach the same way instead of each
 * carrying its own copy. A failure to audit is logged and never fails the read:
 * the row was already authorised by the policy; the ledger is the second line.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CrossHospitalReachRecorder {

    static final String ENTITY_TYPE_PATIENT = "PATIENT";
    static final String DETAIL_ACTING_HOSPITAL_ID = "actingHospitalId";
    static final String DETAIL_SOURCE_HOSPITAL_ID = "sourceHospitalId";
    static final String DETAIL_ROWS_SURFACED = "rowsSurfaced";

    private final AuditEventLogService auditEventLogService;

    /**
     * Count the rows in {@code rows} whose hospital is not {@code actingHospitalId},
     * keyed by source hospital id. Rows with no hospital are not foreign.
     */
    public static <T> Map<String, Long> reachOf(Collection<T> rows, Function<T, UUID> hospitalIdOf, UUID actingHospitalId) {
        Map<String, Long> perSource = new HashMap<>();
        if (rows == null) {
            return perSource;
        }
        for (T row : rows) {
            UUID source = hospitalIdOf.apply(row);
            if (source != null && !source.equals(actingHospitalId)) {
                perSource.merge(source.toString(), 1L, Long::sum);
            }
        }
        return perSource;
    }

    /** Merge {@code more} into {@code into}, summing counts per source hospital. */
    public static Map<String, Long> merge(Map<String, Long> into, Map<String, Long> more) {
        more.forEach((k, v) -> into.merge(k, v, Long::sum));
        return into;
    }

    /**
     * Record one {@code RECORD_SHARE} per entry of {@code perSource}. Empty
     * input records nothing, so callers can pass the reach unconditionally.
     *
     * @param assignmentId the actor's assignment at the acting hospital when
     *                     the caller has it, else {@code null}
     */
    public void record(UUID patientId, UUID actingHospitalId, UUID requesterUserId, UUID assignmentId,
                       Map<String, Long> perSource, String description) {
        if (perSource == null || perSource.isEmpty() || patientId == null) {
            return;
        }
        for (Map.Entry<String, Long> reach : perSource.entrySet()) {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(AuditEventType.RECORD_SHARE)
                    .status(AuditStatus.SUCCESS)
                    .userId(requesterUserId)
                    .assignmentId(assignmentId)
                    .patientId(patientId)
                    .entityType(ENTITY_TYPE_PATIENT)
                    .resourceId(patientId.toString())
                    .eventDescription(description)
                    .details(Map.of(
                        DETAIL_ACTING_HOSPITAL_ID, String.valueOf(actingHospitalId),
                        DETAIL_SOURCE_HOSPITAL_ID, reach.getKey(),
                        DETAIL_ROWS_SURFACED, reach.getValue()))
                    .build());
            } catch (RuntimeException ex) {
                log.warn("[record-access] cross-hospital disclosure audit failed for patient {} source {}: {}",
                    patientId, reach.getKey(), ex.getMessage());
            }
        }
    }
}
