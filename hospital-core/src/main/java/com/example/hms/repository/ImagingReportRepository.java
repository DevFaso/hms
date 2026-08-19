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
}
