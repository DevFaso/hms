package com.example.hms.repository;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.model.ImagingReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
