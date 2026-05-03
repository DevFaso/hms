package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.HospitalLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of a hospital's lifecycle state plus the most-recent
 * transition timestamps. Mirrors {@link TenantLifecycleResponseDTO}
 * for the org level (MVP-c batch — Hospital lifecycle item).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Hospital-lifecycle snapshot.")
public class HospitalLifecycleResponseDTO {

    private UUID hospitalId;
    private String hospitalName;
    private String hospitalCode;
    private UUID organizationId;

    private HospitalLifecycleState lifecycleState;

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

    private boolean canSuspend;
    private boolean canRestore;
    private boolean canArchive;
    private boolean canSchedulePurge;
    private boolean canCancelPurge;
}
