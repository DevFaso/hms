package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * QA D3451 (proxy-read part, r70 → r77): a parent read her daughter's medications, records and imaging through
 * family access, and the daughter's « Qui a consulté mon dossier » listed only the doctor. A read through a
 * family-access grant wrote no audit row at all, so there was nothing to show her.
 *
 * <p>Every read a grant allows now writes one row, keyed to the patient whose record was read: who read (the
 * holder of the grant), under which grant, how they are related and which part of the record (appointments,
 * medications, lab results, billing, records). It is a {@code PATIENT_ACCESS} row told apart by its entity type
 * ({@link #ENTITY_TYPE}), the way an eligibility check is, so {@code DisclosureCategory} lists it to the patient
 * as family access and never as a member of the care team.
 *
 * <p>Ids and the section only. What the read returned is not recorded: the proxy filter (a withheld sensitive
 * category, a released result) decides that, and a row that named it would say what the record holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyReadAudit {

    /** The entity type that makes a {@code PATIENT_ACCESS} row a read through family access. */
    public static final String ENTITY_TYPE = "FamilyAccess";

    /** Keys of the row's details. */
    public static final String DETAIL_SECTION = "section";
    public static final String DETAIL_RELATIONSHIP = "relationship";
    public static final String DETAIL_GRANT = "grantId";

    private static final String PERMISSION_PREFIX = "VIEW_";

    private final AuditEventLogService auditEventLogService;

    /**
     * Records one read the grant allowed. Given the grant's id and relationship, never the grant: this class
     * reads no record and resolves no grantor. Written once the read's transaction commits (at once when there is
     * none), in the audit service's own transaction, and never fails the read.
     *
     * @param permission the scope the read needed ({@code VIEW_MEDICATIONS}); its section is what the row says
     */
    public void record(UUID grantId, ProxyRelationship relationship, UUID patientId, UUID proxyUserId,
                       String permission) {
        if (grantId == null || patientId == null || proxyUserId == null) {
            return;
        }
        String section = sectionOf(permission);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(DETAIL_GRANT, grantId.toString());
        details.put(DETAIL_SECTION, section);
        if (relationship != null) {
            details.put(DETAIL_RELATIONSHIP, relationship.name());
        }
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(AuditEventType.PATIENT_ACCESS)
            .status(AuditStatus.SUCCESS)
            .userId(proxyUserId)
            .patientId(patientId)
            .entityType(ENTITY_TYPE)
            .resourceId(grantId.toString())
            .eventDescription("Record read through family access (" + section + ")")
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[proxy-read] audit not written for grant {}: {}", grantId, ex.getClass().getSimpleName());
            }
        });
    }

    /** {@code VIEW_LAB_RESULTS} → {@code LAB_RESULTS}; an unknown scope is recorded as it is, in upper case. */
    static String sectionOf(String permission) {
        String upper = permission == null ? "" : permission.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith(PERMISSION_PREFIX) ? upper.substring(PERMISSION_PREFIX.length()) : upper;
    }
}
