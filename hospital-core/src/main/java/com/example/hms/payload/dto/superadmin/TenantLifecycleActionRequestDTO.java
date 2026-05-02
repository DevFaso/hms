package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Body for tenant-lifecycle transition endpoints (suspend / archive / schedule-purge).
 *
 * <p>{@code reason} is required for destructive transitions (suspend, archive,
 * schedule-purge); {@code purgeScheduledFor} is consulted only by the
 * schedule-purge endpoint and is ignored elsewhere. Validation of "reason
 * required" is enforced at the service layer because the same DTO serves
 * non-destructive transitions (restore, cancel-purge).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Body for tenant-lifecycle transitions on an organization.")
public class TenantLifecycleActionRequestDTO {

    @Size(max = 1000)
    @Schema(description = "Operator-supplied reason — required for suspend / archive / schedule-purge.",
            example = "Non-payment of Q1 invoice; verbally confirmed by ops 2026-05-02.")
    private String reason;

    @Schema(description = "When schedule-purge should fire. Omit to use the default (now + 30 days).",
            example = "2026-06-01T00:00:00Z")
    private Instant purgeScheduledFor;
}
