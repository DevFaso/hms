package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a radiologist may assert when authoring a report.
 *
 * <p>Everything on this DTO is <em>content</em>. Provenance is not here, and
 * that is the point: before Tier 2 item 26 this class also carried
 * {@code signedByStaffId}, {@code signedAt}, {@code criticalResultAckByStaffId},
 * {@code criticalResultAcknowledgedAt}, {@code reportVersion},
 * {@code latestVersion} and {@code lockedForEditing} — so any caller able to
 * reach the endpoint could assert who signed a radiology report and when, name
 * a different clinician as the acknowledger of a critical finding, or park a
 * report at an arbitrary version. Nothing ever called it, so the hole was
 * never exercised; opening the authoring path is exactly the moment to close
 * it rather than ship it.
 *
 * <p>The server owns instead:
 * <ul>
 *   <li>{@code signedBy} / {@code signedAt} / signature digest — set only by
 *       {@code POST /imaging/results/{id}/sign}, from the authenticated caller.</li>
 *   <li>{@code criticalResultAcknowledgedBy} / {@code ...At} — set only by
 *       {@code PUT /imaging/results/{id}/acknowledge-critical}, likewise.</li>
 *   <li>{@code reportVersion} / {@code latestVersion} — derived from the order's
 *       existing reports, so version numbering cannot be forged or collided.</li>
 *   <li>{@code hospitalId} / {@code organizationId} — taken from the imaging
 *       order and the caller's active scope.</li>
 *   <li>{@code lockedForEditing} — a consequence of signing, not an input.</li>
 * </ul>
 *
 * @see com.example.hms.service.ImagingReportService
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportUpsertRequestDTO {

    /** Required on create; the report's hospital and patient both derive from it. */
    private UUID imagingOrderId;

    private UUID departmentId;

    /** The technologist who acquired the study. Not the author of the read. */
    private UUID performedByStaffId;

    /**
     * The radiologist responsible for the interpretation. Defaults to the
     * authenticated author on create when omitted.
     */
    private UUID interpretingProviderId;

    @Size(max = 80)
    private String reportNumber;

    /**
     * Restricted at the service to the statuses an author may legitimately
     * assert — DRAFT, PRELIMINARY, ADDENDUM, CORRECTED, AMENDED. FINAL is
     * reachable only through the signing ceremony, and CANCELLED / ERROR only
     * through the status endpoint, so a client cannot write itself a
     * dispensable-equivalent state (the TRANSMITTED lesson from PR #463).
     */
    private ImagingReportStatus reportStatus;

    @Size(max = 64)
    private String studyInstanceUid;

    @Size(max = 64)
    private String seriesInstanceUid;

    @Size(max = 80)
    private String accessionNumber;

    @Size(max = 500)
    private String pacsViewerUrl;

    private ImagingModality modality;

    @Size(max = 150)
    private String bodyRegion;

    @Size(max = 255)
    private String reportTitle;

    private LocalDateTime performedAt;

    private LocalDateTime completedAt;

    private String technique;

    private String findings;

    private String impression;

    private String recommendations;

    private String comparisonStudies;

    private Boolean contrastAdministered;

    @Size(max = 1000)
    private String contrastDetails;

    @DecimalMin(value = "0.0", message = "Radiation dose cannot be negative.")
    private BigDecimal radiationDoseMgy;

    /**
     * The author's declaration that this read contains a critical finding —
     * the radiology equivalent of a panic lab value. Stamps
     * {@code criticalResultFlaggedAt} server-side on the transition to true.
     *
     * <p>Set-only, mirroring {@code requiresCosign} on prescriptions and notes:
     * a flag can be raised but not quietly withdrawn, because withdrawing it
     * would erase the evidence that anyone was ever meant to be called.
     */
    private Boolean criticalFinding;

    @Size(max = 150)
    private String externalSystemName;

    @Size(max = 120)
    private String externalReportId;

    private List<ImagingReportMeasurementRequestDTO> measurements;

    private List<ImagingReportAttachmentRequestDTO> attachments;
}
