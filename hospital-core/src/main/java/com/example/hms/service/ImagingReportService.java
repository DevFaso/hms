package com.example.hms.service;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusUpdateRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;

import java.util.List;
import java.util.UUID;

public interface ImagingReportService {

    ImagingReportResponseDTO createReport(ImagingReportUpsertRequestDTO request);

    ImagingReportResponseDTO updateReport(UUID reportId, ImagingReportUpsertRequestDTO request);

    ImagingReportResponseDTO updateReportStatus(UUID reportId, ImagingReportStatusUpdateRequestDTO request);

    ImagingReportResponseDTO getReport(UUID reportId);

    ImagingReportResponseDTO getLatestReportForOrder(UUID imagingOrderId);

    List<ImagingReportResponseDTO> getReportsForOrder(UUID imagingOrderId);

    List<ImagingReportResponseDTO> getReportsByHospitalAndStatus(UUID hospitalId, ImagingReportStatus status);

    List<ImagingReportResponseDTO> getReportsByHospitalAndModality(UUID hospitalId, ImagingModality modality);
}
