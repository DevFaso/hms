package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What the dispensing screen needs to know about the server's pharmacy
 * configuration: whether "Mark ready for collection" is on (G15), and
 * whether work-queue claims are on and how long one lasts (G13).
 * {@code GET /pharmacy/dispense/settings}, same roles as the work queue.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseSettingsDTO {

    /** {@code pharmacy.ready-for-collection.enabled}. */
    private boolean readyForCollectionEnabled;

    /** {@code pharmacy.work-queue.claim.enabled} (G13). */
    private boolean queueClaimEnabled;

    /** {@code pharmacy.work-queue.claim.ttl}, in whole minutes (G13). */
    private long queueClaimTtlMinutes;
}
