package com.example.hms.mapper;

import com.example.hms.model.ImagingReport;
import com.example.hms.model.ImagingReportAttachment;
import com.example.hms.model.ImagingReportMeasurement;
import com.example.hms.model.ImagingReportStatusHistory;
import com.example.hms.enums.ImagingReportHistoryEvent;
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

    /**
     * Resolves the PACS viewer URL: explicit value on the report wins; otherwise
     * substitute the report's UIDs into the hospital's template. Returns null
     * when the template is missing OR when the template references a placeholder
     * the report has no value for — better to surface no link than a broken one
     * with empty-string identifiers.
     */
    private String resolvePacsViewerUrl(ImagingReport report) {
        String explicit = report.getPacsViewerUrl();
        if (explicit != null && !explicit.isBlank()) {
            // A link the browser opens: http(s) only, never javascript: or data:.
            return isWebLink(explicit) ? explicit : null;
        }
        String template = report.getHospital() == null ? null : report.getHospital().getPacsViewerUrlTemplate();
        if (template == null || template.isBlank()) {
            return null;
        }
        return renderViewerTemplate(template, linkStudyUid(report), linkAccession(report));
    }

    /** Only a well-formed UID goes into a link: a value typed on the report cannot add path or query parts. */
    private static String linkStudyUid(ImagingReport report) {
        return com.example.hms.imaging.dicom.DicomUid.isValid(report.getStudyInstanceUid())
            ? report.getStudyInstanceUid() : null;
    }

    /** The accession number URL-encoded, or null when the report has none. */
    private static String linkAccession(ImagingReport report) {
        String accession = report.getAccessionNumber();
        if (accession == null || accession.isBlank()) {
            return null;
        }
        return java.net.URLEncoder.encode(accession.trim(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    /** The template with the report's values, or null when it names one the report lacks or is not http(s). */
    private static String renderViewerTemplate(String template, String studyUid, String accession) {
        if (template.contains("{studyInstanceUid}") && studyUid == null) {
            return null;
        }
        if (template.contains("{accessionNumber}") && accession == null) {
            return null;
        }
        String url = template
                .replace("{studyInstanceUid}", studyUid != null ? studyUid : "")
                .replace("{accessionNumber}", accession != null ? accession : "");
        return isWebLink(url) ? url : null;
    }

    private static boolean isWebLink(String url) {
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** QA D1923: the report's patient is its order's. */
    private static com.example.hms.model.Patient patientOf(ImagingReport report) {
        return report.getImagingOrder() != null ? report.getImagingOrder().getPatient() : null;
    }

    /**
     * QA D1923: the patient's MRN at the report's hospital (else the order's).
     * A registration row whose hospital is gone leaves the MRN out rather than
     * failing the read, as the order mapper does.
     */
    private static String patientMrnOf(ImagingReport report) {
        com.example.hms.model.Patient patient = patientOf(report);
        if (patient == null) {
            return null;
        }
        com.example.hms.model.Hospital hospital = report.getHospital() != null ? report.getHospital()
            : report.getImagingOrder().getHospital();
        if (hospital == null || hospital.getId() == null) {
            return null;
        }
        try {
            return patient.getMrnForHospital(hospital.getId());
        } catch (jakarta.persistence.EntityNotFoundException
                 | org.springframework.orm.jpa.JpaObjectRetrievalFailureException ex) {
            return null;
        }
    }

    public ImagingReportResponseDTO toResponseDTO(ImagingReport report) {
        if (report == null) {
            return null;
        }
        com.example.hms.model.Patient patient = patientOf(report);

        return ImagingReportResponseDTO.builder()
            .id(report.getId())
            .imagingOrderId(report.getImagingOrder() != null ? report.getImagingOrder().getId() : null)
            .patientId(patient != null ? patient.getId() : null)
            .patientDisplayName(patient != null ? patient.getFullName() : null)
            .patientMrn(patientMrnOf(report))
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
            .pacsViewerUrl(resolvePacsViewerUrl(report))
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
            .signed(report.isSigned())
            .signatureAlgorithm(report.getSignatureAlgorithm())
            .signatureValue(report.getSignatureValue())
            .criticalFinding(report.isCriticalFlagged())
            .criticalAcknowledged(report.isCriticalAcknowledged())
            .criticalNotifiedAt(report.getCriticalNotifiedAt())
            .criticalEscalatedAt(report.getCriticalEscalatedAt())
            .criticalEscalationLevel(report.getCriticalEscalationLevel())
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

    /**
     * Copy authorable content onto the report.
     *
     * <p>Content fields are applied unconditionally — a radiologist clearing
     * the Comparison box means the field is now empty, and an omitted field on
     * a full-document PUT is a deletion, not "leave alone".
     *
     * <p>Provenance fields are NOT here at all. This method previously did
     * {@code report.setSignedAt(request.getSignedAt())} and the same for
     * {@code criticalResultAcknowledgedAt} and {@code lockedForEditing},
     * which had two failure modes at once: the client could assert a
     * signature, and any update that omitted those keys silently NULLED a
     * signature the server had stamped. Both are gone — the sign and
     * acknowledge ceremonies own those columns now.
     *
     * <p>{@code reportVersion} and {@code latestVersion} are likewise absent:
     * they are derived from the order's existing reports in the service, so a
     * request can neither forge a version number nor collide two rows onto
     * "latest".
     */
    public void updateReportFromRequest(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (report == null || request == null) {
            return;
        }
        if (request.getReportNumber() != null) {
            report.setReportNumber(request.getReportNumber());
        }
        // The authoring form's fields are its to replace, so a blank one clears.
        report.setAccessionNumber(request.getAccessionNumber());
        report.setModality(request.getModality());
        report.setBodyRegion(request.getBodyRegion());
        report.setReportTitle(request.getReportTitle());
        report.setTechnique(request.getTechnique());
        report.setFindings(request.getFindings());
        report.setImpression(request.getImpression());
        report.setRecommendations(request.getRecommendations());
        report.setComparisonStudies(request.getComparisonStudies());
        report.setContrastAdministered(request.getContrastAdministered());
        report.setContrastDetails(request.getContrastDetails());
        applyPresentAcquisitionFields(report, request);
        synchronizeMeasurements(report, request.getMeasurements());
        synchronizeAttachments(report, request.getAttachments());
    }

    /**
     * QA D1822 class (r51): the study's acquisition and source fields (DICOM
     * UIDs, PACS link, performed and completed times, dose, the external
     * system's ids) are on no authoring form, so a revision of the draft
     * erased them. Absent keeps the stored value; a value sent is applied.
     */
    private static void applyPresentAcquisitionFields(ImagingReport report, ImagingReportUpsertRequestDTO request) {
        if (request.getStudyInstanceUid() != null) {
            report.setStudyInstanceUid(request.getStudyInstanceUid());
        }
        if (request.getSeriesInstanceUid() != null) {
            report.setSeriesInstanceUid(request.getSeriesInstanceUid());
        }
        if (request.getPacsViewerUrl() != null) {
            report.setPacsViewerUrl(request.getPacsViewerUrl());
        }
        if (request.getPerformedAt() != null) {
            report.setPerformedAt(request.getPerformedAt());
        }
        if (request.getCompletedAt() != null) {
            report.setCompletedAt(request.getCompletedAt());
        }
        if (request.getRadiationDoseMgy() != null) {
            report.setRadiationDoseMgy(request.getRadiationDoseMgy());
        }
        if (request.getExternalSystemName() != null) {
            report.setExternalSystemName(request.getExternalSystemName());
        }
        if (request.getExternalReportId() != null) {
            report.setExternalReportId(request.getExternalReportId());
        }
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
            .toList();
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
            .toList();
    }

    private ImagingReportStatusDTO toStatusDTO(ImagingReportStatusHistory history) {
        if (history == null) {
            return null;
        }
        return ImagingReportStatusDTO.builder()
            .id(history.getId())
            .status(history.getStatus())
            .statusReason(history.getStatusReason())
            .eventCode(ImagingReportHistoryEvent.fromStoredText(history.getStatusReason()))
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
            .toList();
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
