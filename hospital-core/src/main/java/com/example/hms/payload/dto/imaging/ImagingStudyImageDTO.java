package com.example.hms.payload.dto.imaging;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One key image of an imaging study, as its reader may see it (V316). The
 * bytes are read from {@code GET /imaging/images/{id}/content}.
 *
 * @param imagingReportId  the report it was attached to; null for an image on the study itself
 * @param uploadedByUserId null for a patient reader
 * @param locked           the report it belongs to is signed (or, for an image on the study,
 *                         the study has a signed report): it can no longer be removed
 * @param deletable        the caller may remove it now (its uploader or a radiologist, while unlocked)
 */
public record ImagingStudyImageDTO(
    UUID id,
    UUID imagingOrderId,
    UUID imagingReportId,
    String contentType,
    long sizeBytes,
    String caption,
    LocalDateTime uploadedAt,
    UUID uploadedByUserId,
    boolean locked,
    boolean deletable
) {
}
