package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingReportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingReportResponseDTO {

    private UUID id;

    private UUID imagingOrderId;

    private UUID hospitalId;

    private UUID organizationId;

    private UUID departmentId;

    private String reportNumber;

    private ImagingReportStatus reportStatus;

    private Integer reportVersion;

    private Boolean latestVersion;

    private String studyInstanceUid;

    private String seriesInstanceUid;

    private String accessionNumber;

    private String pacsViewerUrl;

    private ImagingModality modality;

    private String bodyRegion;

    private String reportTitle;

    private UUID performedByStaffId;

    private String performedByName;

    private UUID interpretingProviderId;

    private String interpretingProviderName;

    private UUID signedByStaffId;

    private String signedByName;

    private UUID criticalResultAckByStaffId;

    private String criticalResultAckByName;

    private LocalDateTime performedAt;

    private LocalDateTime completedAt;

    private LocalDateTime interpretedAt;

    private LocalDateTime signedAt;

    private LocalDateTime criticalResultFlaggedAt;

    private LocalDateTime criticalResultAcknowledgedAt;

    private String technique;

    private String findings;

    private String impression;

    private String recommendations;

    private String comparisonStudies;

    private Boolean contrastAdministered;

    private String contrastDetails;

    private BigDecimal radiationDoseMgy;

    private Integer attachmentsCount;

    private Integer measurementsCount;

    private LocalDateTime lastStatusSyncedAt;

    private LocalDateTime patientNotifiedAt;

    private Boolean patientNotified;

    private Boolean lockedForEditing;

    private String lockReason;

    private String externalSystemName;

    private String externalReportId;

    private UUID createdBy;

    private UUID updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<ImagingReportMeasurementDTO> measurements;

    private List<ImagingReportAttachmentDTO> attachments;

    private List<ImagingReportStatusDTO> statusHistory;
}
