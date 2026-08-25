package com.example.hms.service;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;

import java.util.List;
import java.util.UUID;

/**
 * Authoring and retrieval of radiology reports.
 *
 * <p>Every method here is hospital-scoped against the caller's active
 * assignment ({@code null} scope = super-admin, unscoped), and every miss is a
 * 404 rather than a 403 — a report at another hospital must be
 * indistinguishable from one that does not exist, which is the stance PR #483
 * applied to lab acknowledge and read-back for the same reason.
 *
 * <p>The write surface splits deliberately into content and ceremony:
 * {@link #createReport} and {@link #updateReport} take what a radiologist
 * types, while {@link #signReport} and {@link #acknowledgeCriticalResult}
 * stamp identity and time from the authenticated caller. Nothing on the
 * request side can assert either.
 */
public interface ImagingReportService {

    /**
     * Author a new report against an imaging order.
     *
     * <p>Version numbering is derived, never asserted: the first report on an
     * order is version 1, each subsequent one is N+1, and the new row takes
     * over as latest while the previous latest is demoted.
     *
     * <p>A second report on an order that already has a signed one is how a
     * correction is made — it must declare ADDENDUM, CORRECTED or AMENDED,
     * because silently opening a fresh PRELIMINARY over a signed read would
     * hide that the record changed after sign-off.
     */
    ImagingReportResponseDTO createReport(ImagingReportUpsertRequestDTO request);

    /**
     * Revise an <em>unsigned</em> report in place. Signing closes a report to
     * content edits; corrections after that go through {@link #createReport}
     * as a new version.
     */
    ImagingReportResponseDTO updateReport(UUID reportId, ImagingReportUpsertRequestDTO request);

    /**
     * The signing ceremony — the only path to {@link ImagingReportStatus#FINAL}.
     *
     * <p>Stamps the authenticated caller as signer, the server clock as sign
     * time, and a SHA-256 digest over a canonical payload as tamper-evidence
     * (V132), mirroring the prescription ceremony of PR #455 and the encounter
     * note ceremony of PR #473. Re-signing is refused: a second signature over
     * altered content would silently replace the only record of what was
     * attested.
     */
    ImagingReportResponseDTO signReport(UUID reportId);

    /**
     * Record that a clinician has taken responsibility for a critical finding.
     *
     * <p>Stamps {@code criticalResultAcknowledgedBy} and {@code ...At} from the
     * authenticated caller. Refused when the report carries no critical flag —
     * acknowledging a finding nobody raised would put a false receipt in the
     * record.
     */
    ImagingReportResponseDTO acknowledgeCriticalResult(UUID reportId);

    /**
     * Move a report to a terminal administrative state (CANCELLED / ERROR) and
     * record why. Deliberately cannot reach FINAL — that is {@link #signReport}.
     */
    ImagingReportResponseDTO updateReportStatus(UUID reportId, ImagingReportStatusUpdateRequestDTO request);

    ImagingReportResponseDTO getReport(UUID reportId);

    ImagingReportResponseDTO getLatestReportForOrder(UUID imagingOrderId);

    List<ImagingReportResponseDTO> getReportsForOrder(UUID imagingOrderId);

    List<ImagingReportResponseDTO> getReportsByHospitalAndStatus(UUID hospitalId, ImagingReportStatus status);

    List<ImagingReportResponseDTO> getReportsByHospitalAndModality(UUID hospitalId, ImagingModality modality);
}
