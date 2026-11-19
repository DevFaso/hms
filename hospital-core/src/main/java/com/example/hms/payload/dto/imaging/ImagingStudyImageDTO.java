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
 * @param captionEditable  the caller may correct its caption (its uploader or a radiologist; QA D2466)
 * @param withdrawable     the caller may withdraw it, with a reason, now that it is locked (a radiologist
 *                         or the report's signer; QA D2464)
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
    boolean deletable,
    boolean captionEditable,
    boolean withdrawable
) {
}
