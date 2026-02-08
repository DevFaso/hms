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
public class ImagingReportUpsertRequestDTO {

    private UUID imagingOrderId;

    private UUID hospitalId;

    private UUID organizationId;

    private UUID departmentId;

    private UUID performedByStaffId;

    private UUID interpretingProviderId;

    private UUID signedByStaffId;

    private UUID criticalResultAckByStaffId;

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

    private LocalDateTime performedAt;

    private LocalDateTime completedAt;

    private LocalDateTime interpretedAt;

    private LocalDateTime signedAt;

    private LocalDateTime criticalResultFlaggedAt;

    private LocalDateTime criticalResultAcknowledgedAt;

    private LocalDateTime patientNotifiedAt;

    private String technique;

    private String findings;

    private String impression;

    private String recommendations;

    private String comparisonStudies;

    private Boolean contrastAdministered;

    private String contrastDetails;

    private BigDecimal radiationDoseMgy;

    private Boolean lockedForEditing;

    private String lockReason;

    private String externalSystemName;

    private String externalReportId;

    private List<ImagingReportMeasurementRequestDTO> measurements;

    private List<ImagingReportAttachmentRequestDTO> attachments;
}
