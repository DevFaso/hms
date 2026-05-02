package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.OrganizationLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of an organization's lifecycle state plus the most-recent transition
 * timestamps and the operators who performed each transition. Returned by the
 * lifecycle endpoints so the UI can render a state chip + history panel without
 * re-querying the audit log.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Tenant-lifecycle snapshot for an organization.")
public class TenantLifecycleResponseDTO {

    private UUID organizationId;
    private String organizationName;
    private String organizationCode;

    private OrganizationLifecycleState lifecycleState;

    private Instant suspendedAt;
    private UUID suspendedBy;
    private String suspensionReason;

    private Instant archivedAt;
    private UUID archivedBy;
    private String archiveReason;

    private Instant purgeScheduledFor;
    private UUID purgeScheduledBy;
    private String purgeReason;
    private Instant purgedAt;

    /** Computed transition flags so the UI does not duplicate the state-machine logic. */
    private boolean canSuspend;
    private boolean canRestore;
    private boolean canArchive;
    private boolean canSchedulePurge;
    private boolean canCancelPurge;
}
