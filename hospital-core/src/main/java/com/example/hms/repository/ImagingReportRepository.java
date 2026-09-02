package com.example.hms.repository;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.model.ImagingReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImagingReportRepository extends JpaRepository<ImagingReport, UUID> {

    Optional<ImagingReport> findFirstByImagingOrder_IdAndLatestVersionIsTrue(UUID imagingOrderId);

    Optional<ImagingReport> findTopByImagingOrder_IdOrderByReportVersionDesc(UUID imagingOrderId);

    List<ImagingReport> findByImagingOrder_IdOrderByReportVersionDesc(UUID imagingOrderId);

    List<ImagingReport> findByHospital_IdAndReportStatusOrderByPerformedAtDesc(UUID hospitalId, ImagingReportStatus status);

    List<ImagingReport> findByHospital_IdAndModalityOrderByPerformedAtDesc(UUID hospitalId, ImagingModality modality);

    Optional<ImagingReport> findByReportNumberAndHospital_Id(String reportNumber, UUID hospitalId);

    boolean existsByImagingOrder_IdAndReportStatus(UUID imagingOrderId, ImagingReportStatus status);

    /**
     * Batch lookup used by the chart-review aggregator so the latest
     * imaging report (i.e. the row flagged {@code latest_version=true})
     * can be resolved for every imaging order on the current page in one
     * round-trip instead of two queries per order.
     */
    List<ImagingReport> findByImagingOrder_IdInAndLatestVersionIsTrue(Collection<UUID> imagingOrderIds);

    /**
     * FHIR DiagnosticReport search (Tier 2 item 42): the patient's latest
     * report versions, paged over REPORTS rather than candidate orders — a
     * cap applied to orders would let a run of resultless recent orders push
     * older valid reports out of the window entirely.
     */
    org.springframework.data.domain.Page<ImagingReport>
        findByImagingOrder_Patient_IdAndHospital_IdAndLatestVersionIsTrueOrderByPerformedAtDesc(
            UUID patientId, UUID hospitalId, org.springframework.data.domain.Pageable pageable);

    /**
     * Fallback batch lookup for orders whose reports were not flagged with
     * {@code latest_version=true} — the chart-review aggregator picks the
     * highest {@code reportVersion} per order from this list in memory.
     */
    List<ImagingReport> findByImagingOrder_IdIn(Collection<UUID> imagingOrderIds);

    /**
     * Cross-tenant guard for the DICOM proxy (row 42 follow-on). The
     * upstream DICOMweb server is queried by caller-supplied
     * {@code studyInstanceUid}; without this check, a caller in
     * tenant A could enumerate studies recorded at tenant B simply by
     * guessing a UID. Returns true only when the UID resolves to a
     * report at the active hospital scope.
     */
    boolean existsByHospital_IdAndStudyInstanceUid(UUID hospitalId, String studyInstanceUid);

    /**
     * Critical findings whose first alert has gone out and which nobody has
     * acknowledged since (Tier 2 item 27).
     *
     * <p>Mirrors {@code LabResultRepository.findCriticalAwaitingEscalation}.
     * Two details matter and are the reason that query looks the way it does:
     *
     * <p>{@code criticalNotifiedAt IS NOT NULL} — a report never notified has
     * no first alert to escalate from, so it must not skip straight to the
     * chain.
     *
     * <p>{@code criticalEscalatedAt IS NULL OR < :cutoff} — this is what makes
     * the sweep REPEAT. A query that excluded every already-escalated row would
     * fire once and then go silent on a finding nobody has acknowledged, which
     * is the failure mode the whole loop exists to prevent.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM ImagingReport r WHERE r.criticalResultFlaggedAt IS NOT NULL "
        + "AND r.criticalResultAcknowledgedAt IS NULL "
        + "AND r.criticalNotifiedAt IS NOT NULL AND r.criticalNotifiedAt < :cutoff "
        + "AND (r.criticalEscalatedAt IS NULL OR r.criticalEscalatedAt < :cutoff)")
    List<ImagingReport> findCriticalAwaitingEscalation(
        @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
