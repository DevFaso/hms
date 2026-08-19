package com.example.hms.payload.dto.orderset;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/order-sets/{orderSetId}/apply/{admissionId}}.
 *
 * <p>{@code encounterId} is optional. {@code PrescriptionService} resolves
 * or creates an encounter when one is not supplied; {@code LabOrderService}
 * accepts a null encounter. {@code ImagingOrderService} requires one — the
 * dispatcher returns a skipped result for IMAGING items when no encounter
 * is supplied so the rest of the bundle still applies cleanly.
 */
public record ApplyOrderSetRequestDTO(
    UUID encounterId,
    @NotNull UUID orderingStaffId,
    Boolean forceOverride
) {}
