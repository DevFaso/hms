package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What the dispensing screen needs to know about the server's pharmacy
 * configuration (G15): today only whether "Mark ready for collection" is on.
 * {@code GET /pharmacy/dispense/settings}, same roles as the work queue.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseSettingsDTO {

    /** {@code pharmacy.ready-for-collection.enabled}. */
    private boolean readyForCollectionEnabled;
}
