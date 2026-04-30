package com.example.hms.payload.dto.orderset;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;

import java.util.List;
import java.util.UUID;

/**
 * Response body for a successful order-set application. Lists the IDs
 * of every persisted order so the front-end can deep-link, plus any
 * non-blocking CDS advisories the rule engine emitted on the
 * medication fan-out.
 *
 * <p>If a critical CDS advisory blocks the apply, the controller
 * returns 400 with the existing {@code CdsCriticalBlockException}
 * structured-error contract instead of this DTO.
 */
public record AppliedOrderSetSummaryDTO(
    UUID orderSetId,
    String orderSetName,
    int orderSetVersion,
    UUID admissionId,
    UUID encounterId,
    List<UUID> prescriptionIds,
    List<UUID> labOrderIds,
    List<UUID> imagingOrderIds,
    int skippedItemCount,
    List<CdsCard> cdsAdvisories
) {

    public int totalCreated() {
        return prescriptionIds.size() + labOrderIds.size() + imagingOrderIds.size();
    }
}
