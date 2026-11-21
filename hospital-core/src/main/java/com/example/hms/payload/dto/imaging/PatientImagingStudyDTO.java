package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * QA D2342 (r63): one imaging study as its patient (or their authorised proxy)
 * reads it on the portal's « Imagerie » page.
 *
 * <p>Only a study with at least one released report is listed. {@code reports}
 * holds every released version, oldest first (the signed original, then its
 * signed addenda), each one the patient's copy
 * ({@code PatientSafeView.imagingReport}): no status trail, no signature
 * digest, no critical-result escalation, no working measurements. Drafts and
 * unsigned versions never appear. {@code images} are the key images released
 * with the study (#1223's rule), read through
 * {@code GET /me/patient/imaging/images/{id}/content}.
 */
public record PatientImagingStudyDTO(
    UUID imagingOrderId,
    ImagingModality modality,
    String studyType,
    String bodyRegion,
    ImagingLaterality laterality,
    LocalDateTime orderedAt,
    String hospitalName,
    List<ImagingReportResponseDTO> reports,
    List<ImagingStudyImageDTO> images
) {
}
