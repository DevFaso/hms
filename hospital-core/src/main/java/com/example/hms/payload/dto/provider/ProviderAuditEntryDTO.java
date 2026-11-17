package com.example.hms.payload.dto.provider;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of a provider facility's own audit trail ({@code GET /provider/audit},
 * provider plan §3.1): what a member of its staff did, when, and on which kind
 * of record. Ids and codes only, by construction: no description, no details,
 * no IP address and no patient, so nothing a write recorded in free text can
 * reach the facility's admin (plan §6.9).
 *
 * <p>The argument order of the all-args constructor is the JPQL constructor
 * expression's in {@code AuditEventLogRepository.findProviderFacilityTrail}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One audit row of the caller's provider facility: ids and codes, no patient data.")
public class ProviderAuditEntryDTO {

    private UUID id;
    private LocalDateTime eventTimestamp;
    private AuditEventType eventType;
    private AuditStatus status;
    private UUID actorUserId;
    /** The acting staff member's name as the row recorded it (a member of this facility's staff). */
    private String actorName;
    /** The ACTOR's own role at the facility (the role they act in there), never the role a row assigned. */
    private String roleName;
    /** The kind of record acted on, e.g. PROVIDER_FACILITY or PROVIDER_STAFF. */
    private String entityType;
    /** The id of the record acted on, when there is one. */
    private String resourceId;

    /** For a role-assignment row, the role it assigned or confirmed: the row's subject, not the actor's role. */
    private String subjectRole;

    /**
     * Set when platform support acted while impersonating the member named as
     * the actor: who really acted. {@code null} for an ordinary row.
     */
    private UUID impersonatorUserId;

    /** The impersonating support user's username, with {@link #impersonatorUserId}. */
    private String impersonatorUsername;
}
