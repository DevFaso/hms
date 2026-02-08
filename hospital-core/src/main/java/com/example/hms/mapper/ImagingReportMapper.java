package com.example.hms.mapper;

import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportAttachment;
import com.example.hms.model.ImagingReportMeasurement;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.payload.dto.imaging.ImagingReportAttachmentDTO;
import com.example.hms.payload.dto.imaging.ImagingReportAttachmentRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportMeasurementDTO;
import com.example.hms.payload.dto.imaging.ImagingReportMeasurementRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingReportStatusDTO;
import com.example.hms.payload.dto.imaging.ImagingReportUpsertRequestDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ImagingReportMapper {

    public ImagingReportResponseDTO toResponseDTO(ImagingReport report) {
        if (report == null) {
            return null;
        }

        return ImagingReportResponseDTO.builder()
            .id(report.getId())
            .imagingOrderId(report.getImagingOrder() != null ? report.getImagingOrder().getId() : null)
            .hospitalId(report.getHospital() != null ? report.getHospital().getId() : null)
            .organizationId(report.getOrganization() != null ? report.getOrganization().getId() : null)
            .departmentId(report.getDepartment() != null ? report.getDepartment().getId() : null)
            .reportNumber(report.getReportNumber())
            .reportStatus(report.getReportStatus())
            .reportVersion(report.getReportVersion())
            .latestVersion(report.getLatestVersion())
            .studyInstanceUid(report.getStudyInstanceUid())
            .seriesInstanceUid(report.getSeriesInstanceUid())
            .accessionNumber(report.getAccessionNumber())
            .pacsViewerUrl(report.getPacsViewerUrl())
            .modality(report.getModality())
            .bodyRegion(report.getBodyRegion())
            .reportTitle(report.getReportTitle())
            .performedByStaffId(report.getPerformedBy() != null ? report.getPerformedBy().getId() : null)
            .performedByName(report.getPerformedBy() != null ? report.getPerformedBy().getFullName() : null)
            .interpretingProviderId(report.getInterpretingProvider() != null ? report.getInterpretingProvider().getId() : null)
            .interpretingProviderName(report.getInterpretingProvider() != null ? report.getInterpretingProvider().getFullName() : null)
            .signedByStaffId(report.getSignedBy() != null ? report.getSignedBy().getId() : null)
            .signedByName(report.getSignedBy() != null ? report.getSignedBy().getFullName() : null)
            .criticalResultAckByStaffId(report.getCriticalResultAcknowledgedBy() != null ? report.getCriticalResultAcknowledgedBy().getId() : null)
            .criticalResultAckByName(report.getCriticalResultAcknowledgedBy() != null ? report.getCriticalResultAcknowledgedBy().getFullName() : null)
            .performedAt(report.getPerformedAt())
            .completedAt(report.getCompletedAt())
            .interpretedAt(report.getInterpretedAt())
            .signedAt(report.getSignedAt())
            .criticalResultFlaggedAt(report.getCriticalResultFlaggedAt())
            .criticalResultAcknowledgedAt(report.getCriticalResultAcknowledgedAt())
            .technique(report.getTechnique())
            .findings(report.getFindings())
            .impression(report.getImpression())
            .recommendations(report.getRecommendations())
            .comparisonStudies(report.getComparisonStudies())
            .contrastAdministered(report.getContrastAdministered())
            .contrastDetails(report.getContrastDetails())
            .radiationDoseMgy(report.getRadiationDoseMgy())
            .attachmentsCount(report.getAttachmentsCount())
            .measurementsCount(report.getMeasurementsCount())
            .lastStatusSyncedAt(report.getLastStatusSyncedAt())
            .patientNotifiedAt(report.getPatientNotifiedAt())
            .patientNotified(report.isPatientNotified())
            .lockedForEditing(report.getLockedForEditing())
            .lockReason(report.getLockReason())
            .externalSystemName(report.getExternalSystemName())
            .externalReportId(report.getExternalReportId())
            .createdBy(report.getCreatedBy())
            .updatedBy(report.getUpdatedBy())
            .createdAt(report.getCreatedAt())
            .updatedAt(report.getUpdatedAt())
            .measurements(toMeasurementDTOs(report.getMeasurements()))
            .attachments(toAttachmentDTOs(report.getAttachments()))
            .statusHistory(toStatusDTOs(report.getStatusHistory()))
            .build();
    }

    public void updateReportFromRequest(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (report == null || request == null) {
            return;
        }
        if (request.getReportNumber() != null) {
            report.setReportNumber(request.getReportNumber());
        }
        if (request.getReportStatus() != null) {
            report.setReportStatus(request.getReportStatus());
        }
        if (request.getReportVersion() != null) {
            report.setReportVersion(request.getReportVersion());
        }
        if (request.getLatestVersion() != null) {
            report.setLatestVersion(request.getLatestVersion());
        }
        report.setStudyInstanceUid(request.getStudyInstanceUid());
        report.setSeriesInstanceUid(request.getSeriesInstanceUid());
        report.setAccessionNumber(request.getAccessionNumber());
        report.setPacsViewerUrl(request.getPacsViewerUrl());
        report.setModality(request.getModality());
        report.setBodyRegion(request.getBodyRegion());
        report.setReportTitle(request.getReportTitle());
        report.setPerformedAt(request.getPerformedAt());
        report.setCompletedAt(request.getCompletedAt());
        report.setInterpretedAt(request.getInterpretedAt());
        report.setSignedAt(request.getSignedAt());
        report.setCriticalResultFlaggedAt(request.getCriticalResultFlaggedAt());
        report.setCriticalResultAcknowledgedAt(request.getCriticalResultAcknowledgedAt());
        report.setPatientNotifiedAt(request.getPatientNotifiedAt());
        report.setTechnique(request.getTechnique());
        report.setFindings(request.getFindings());
        report.setImpression(request.getImpression());
        report.setRecommendations(request.getRecommendations());
        report.setComparisonStudies(request.getComparisonStudies());
        report.setContrastAdministered(request.getContrastAdministered());
        report.setContrastDetails(request.getContrastDetails());
        report.setRadiationDoseMgy(request.getRadiationDoseMgy());
        report.setLockedForEditing(request.getLockedForEditing());
        report.setLockReason(request.getLockReason());
        report.setExternalSystemName(request.getExternalSystemName());
        report.setExternalReportId(request.getExternalReportId());
        synchronizeMeasurements(report, request.getMeasurements());
        synchronizeAttachments(report, request.getAttachments());
    }

    public void synchronizeMeasurements(ImagingReport report, List<ImagingReportMeasurementRequestDTO> measurementRequests) {
        if (report == null || measurementRequests == null) {
            return;
        }

        if (measurementRequests.isEmpty()) {
            report.getMeasurements().clear();
            report.setMeasurementsCount(0);
            return;
        }

        Map<UUID, ImagingReportMeasurement> existingById = report.getMeasurements().stream()
            .filter(measurement -> measurement.getId() != null)
            .collect(Collectors.toMap(ImagingReportMeasurement::getId, measurement -> measurement));

        List<ImagingReportMeasurement> updated = new ArrayList<>(measurementRequests.size());
        int fallbackSequence = 1;
        for (ImagingReportMeasurementRequestDTO dto : measurementRequests) {
            Integer sequence = dto.getSequenceNumber();
            if (sequence == null) {
                sequence = fallbackSequence++;
            }
            ImagingReportMeasurement measurement = mapMeasurementRequest(report, dto, existingById, sequence);
            updated.add(measurement);
        }
        updated.sort(Comparator.comparing(m -> m.getSequenceNumber() != null ? m.getSequenceNumber() : Integer.MAX_VALUE));

        report.getMeasurements().clear();
        report.getMeasurements().addAll(updated);
        report.setMeasurementsCount(report.getMeasurements().size());
    }

    public void synchronizeAttachments(ImagingReport report, List<ImagingReportAttachmentRequestDTO> attachmentRequests) {
        if (report == null || attachmentRequests == null) {
            return;
        }
        if (attachmentRequests.isEmpty()) {
            report.getAttachments().clear();
            report.setAttachmentsCount(0);
            return;
        }

        Map<Integer, ImagingReportAttachment> existingByPosition = report.getAttachments().stream()
            .filter(attachment -> attachment.getPosition() != null)
            .collect(Collectors.toMap(ImagingReportAttachment::getPosition, attachment -> attachment, (first, second) -> first, HashMap::new));

        List<ImagingReportAttachment> updated = new ArrayList<>(attachmentRequests.size());
        int fallbackPosition = 1;
        for (ImagingReportAttachmentRequestDTO dto : attachmentRequests) {
            Integer targetPosition = dto.getPosition() != null ? dto.getPosition() : fallbackPosition++;
            ImagingReportAttachment attachment = existingByPosition.remove(targetPosition);
            if (attachment == null) {
                attachment = new ImagingReportAttachment();
            }
            attachment.setReport(report);
            attachment.setPosition(targetPosition);
            attachment.setStorageKey(dto.getStorageKey());
            attachment.setStorageBucket(dto.getStorageBucket());
            attachment.setFileName(dto.getFileName());
            attachment.setContentType(dto.getContentType());
            attachment.setSizeBytes(dto.getSizeBytes());
            attachment.setDicomObjectUid(dto.getDicomObjectUid());
            attachment.setViewerUrl(dto.getViewerUrl());
            attachment.setThumbnailKey(dto.getThumbnailKey());
            attachment.setLabel(dto.getLabel());
            attachment.setCategory(dto.getCategory());
            updated.add(attachment);
        }
        updated.sort(Comparator.comparing(ImagingReportAttachment::getPosition));

        report.getAttachments().clear();
        report.getAttachments().addAll(updated);
        report.setAttachmentsCount(report.getAttachments().size());
    }

    private ImagingReportAttachmentDTO toAttachmentDTO(ImagingReportAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        return ImagingReportAttachmentDTO.builder()
            .position(attachment.getPosition())
            .storageKey(attachment.getStorageKey())
            .storageBucket(attachment.getStorageBucket())
            .fileName(attachment.getFileName())
            .contentType(attachment.getContentType())
            .sizeBytes(attachment.getSizeBytes())
            .dicomObjectUid(attachment.getDicomObjectUid())
            .viewerUrl(attachment.getViewerUrl())
            .thumbnailKey(attachment.getThumbnailKey())
            .label(attachment.getLabel())
            .category(attachment.getCategory())
            .createdAt(attachment.getCreatedAt())
            .build();
    }

    private List<ImagingReportAttachmentDTO> toAttachmentDTOs(List<ImagingReportAttachment> attachments) {
        if (CollectionUtils.isEmpty(attachments)) {
            return List.of();
        }
        return attachments.stream()
            .filter(Objects::nonNull)
            .map(this::toAttachmentDTO)
            .collect(Collectors.toList());
    }

    private ImagingReportMeasurementDTO toMeasurementDTO(ImagingReportMeasurement measurement) {
        if (measurement == null) {
            return null;
        }
        return ImagingReportMeasurementDTO.builder()
            .id(measurement.getId())
            .sequenceNumber(measurement.getSequenceNumber())
            .label(measurement.getLabel())
            .region(measurement.getRegion())
            .plane(measurement.getPlane())
            .modifier(measurement.getModifier())
            .numericValue(measurement.getNumericValue())
            .textValue(measurement.getTextValue())
            .unit(measurement.getUnit())
            .referenceMin(measurement.getReferenceMin())
            .referenceMax(measurement.getReferenceMax())
            .abnormal(measurement.getAbnormal())
            .notes(measurement.getNotes())
            .createdAt(measurement.getCreatedAt())
            .build();
    }

    private List<ImagingReportMeasurementDTO> toMeasurementDTOs(List<ImagingReportMeasurement> measurements) {
        if (CollectionUtils.isEmpty(measurements)) {
            return List.of();
        }
        return measurements.stream()
            .filter(Objects::nonNull)
            .map(this::toMeasurementDTO)
            .collect(Collectors.toList());
    }

    private ImagingReportStatusDTO toStatusDTO(ImagingReportStatusHistory history) {
        if (history == null) {
            return null;
        }
        return ImagingReportStatusDTO.builder()
            .id(history.getId())
            .status(history.getStatus())
            .statusReason(history.getStatusReason())
            .changedAt(history.getChangedAt())
            .changedByStaffId(history.getChangedBy() != null ? history.getChangedBy().getId() : null)
            .changedByName(history.getChangedByName())
            .clientSource(history.getClientSource())
            .notes(history.getNotes())
            .build();
    }

    private List<ImagingReportStatusDTO> toStatusDTOs(List<ImagingReportStatusHistory> history) {
        if (CollectionUtils.isEmpty(history)) {
            return List.of();
        }
        return history.stream()
            .filter(Objects::nonNull)
            .map(this::toStatusDTO)
            .collect(Collectors.toList());
    }

    private ImagingReportMeasurement mapMeasurementRequest(ImagingReport report,
                                                          ImagingReportMeasurementRequestDTO dto,
                                                          Map<UUID, ImagingReportMeasurement> existingById,
                                                          Integer sequenceNumber) {
        ImagingReportMeasurement measurement = null;
        if (dto.getId() != null) {
            measurement = existingById.remove(dto.getId());
        }
        if (measurement == null) {
            measurement = new ImagingReportMeasurement();
        }
        measurement.setReport(report);
        measurement.setSequenceNumber(sequenceNumber);
        measurement.setLabel(dto.getLabel());
        measurement.setRegion(dto.getRegion());
        measurement.setPlane(dto.getPlane());
        measurement.setModifier(dto.getModifier());
        measurement.setNumericValue(dto.getNumericValue());
        measurement.setTextValue(dto.getTextValue());
        measurement.setUnit(dto.getUnit());
        measurement.setReferenceMin(dto.getReferenceMin());
        measurement.setReferenceMax(dto.getReferenceMax());
        measurement.setAbnormal(dto.getAbnormal());
        measurement.setNotes(dto.getNotes());
        return measurement;
    }
}
