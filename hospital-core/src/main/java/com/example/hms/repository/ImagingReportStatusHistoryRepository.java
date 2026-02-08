package com.example.hms.repository;

import com.example.hms.model.ImagingReportStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ImagingReportStatusHistoryRepository extends JpaRepository<ImagingReportStatusHistory, UUID> {

    List<ImagingReportStatusHistory> findByImagingReport_IdOrderByChangedAtDesc(UUID imagingReportId);

    List<ImagingReportStatusHistory> findByImagingOrder_IdOrderByChangedAtDesc(UUID imagingOrderId);

    List<ImagingReportStatusHistory> findByImagingReport_IdAndChangedAtAfterOrderByChangedAtDesc(UUID imagingReportId, LocalDateTime changedAfter);
}
