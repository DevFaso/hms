package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * G13: an active work-queue claim, on a queue row and as the answer of the
 * claim and take-over endpoints. Only the four pharmacy-queue roles read it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkQueueClaimDTO {
    private UUID prescriptionId;
    private UUID claimedByUserId;
    private String claimedByName;
    private LocalDateTime claimedAt;
    /** {@code claimedAt + ttl}, for display; expiry is computed on every read. */
    private LocalDateTime expiresAt;
    /** True only for the caller's own claim. */
    private boolean mine;
    /**
     * Claim and take-over responses only: true when the caller already held
     * an active claim and this call renewed it. Absent on queue rows.
     */
    private Boolean renewed;
}
