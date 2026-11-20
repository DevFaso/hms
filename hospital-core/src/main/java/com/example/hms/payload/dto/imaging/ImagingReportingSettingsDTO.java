package com.example.hms.payload.dto.imaging;

import java.util.UUID;

/**
 * Policy rule 3 (V346, provisional): a hospital's imaging reporting settings.
 *
 * @param ultrasoundNonPhysicianReporting « Échographie : compte rendu par un non-radiologue »;
 *                                        false (the default) keeps ultrasound reports with physicians
 */
public record ImagingReportingSettingsDTO(UUID hospitalId, boolean ultrasoundNonPhysicianReporting) {
}
