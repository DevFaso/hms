package com.example.hms.payload.dto.imaging;

import java.util.UUID;

/**
 * Policy rule 3 (V346, provisional): a hospital's imaging reporting settings.
 *
 * @param ultrasoundByNonPhysician « Échographie par un non-médecin »: false (the default) keeps the
 *                                 ultrasound exam with physicians; true lets an imaging technician
 *                                 perform it. The report stays a physician's either way.
 */
public record ImagingReportingSettingsDTO(UUID hospitalId, boolean ultrasoundByNonPhysician) {
}
