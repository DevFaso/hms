package com.example.hms.repository;

import com.example.hms.model.ImagingReportAttachment;
import com.example.hms.model.ImagingReportAttachmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ImagingReportAttachmentRepository extends JpaRepository<ImagingReportAttachment, ImagingReportAttachmentId> {

    List<ImagingReportAttachment> findByReport_IdOrderByPositionAsc(UUID reportId);

    void deleteByReport_Id(UUID reportId);
}
