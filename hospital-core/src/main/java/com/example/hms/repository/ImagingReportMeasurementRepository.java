package com.example.hms.repository;

import com.example.hms.model.ImagingReportMeasurement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImagingReportMeasurementRepository extends JpaRepository<ImagingReportMeasurement, UUID> {

    List<ImagingReportMeasurement> findByReport_IdOrderBySequenceNumberAsc(UUID reportId);

    void deleteByReport_Id(UUID reportId);
}
