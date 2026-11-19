package com.example.hms.payload.dto.imaging.pacs;

import java.util.List;
import java.util.UUID;

/**
 * What the in-app viewer needs to show a report's images.
 *
 * @param status     AVAILABLE, PROXY_DISABLED, PACS_NOT_CONFIGURED, NO_STUDY_LINKED,
 *                   STUDY_NOT_FOUND, ACCESSION_AMBIGUOUS or PACS_UNREACHABLE; the portal
 *                   words each one
 * @param linkedBy   STUDY_UID (on the report) or ACCESSION (matched in the hospital's own PACS); null when unlinked
 * @param instances  sorted by series then instance number; empty unless AVAILABLE
 * @param truncated  the study has more instances than one manifest lists
 */
public record ImagingStudyViewerDTO(
    UUID reportId,
    String status,
    String linkedBy,
    List<ImagingStudyInstanceDTO> instances,
    boolean truncated
) {

    /** One image (or multi-frame image) the viewer can request rendered. */
    public record ImagingStudyInstanceDTO(
        String seriesInstanceUid,
        String sopInstanceUid,
        Integer seriesNumber,
        Integer instanceNumber,
        int numberOfFrames,
        String modality
    ) {
    }
}
