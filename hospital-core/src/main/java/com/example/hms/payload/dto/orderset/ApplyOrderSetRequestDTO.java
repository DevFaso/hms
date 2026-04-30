package com.example.hms.payload.dto.orderset;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/admissions/{id}/order-sets/{orderSetId}/apply}.
 *
 * <p>The encounter id is required because every fanned-out order
 * (Prescription, LabOrder, ImagingOrder) anchors to an encounter — the
 * picker UI captures the active encounter in the clinician's context
 * before posting.
 */
public record ApplyOrderSetRequestDTO(
    @NotNull UUID encounterId,
    @NotNull UUID orderingStaffId,
    Boolean forceOverride
) {}
