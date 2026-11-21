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

    /**
     * QA D1923 (r53): whose report it is. The results list and the report
     * drawer named no patient, so a report was revised and signed with no name
     * or MRN on screen. The MRN is the one of the report's hospital.
     */
    private UUID patientId;

    private String patientDisplayName;

    private String patientMrn;

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

    /**
     * QA D2912 (r66): the exam record of the report's study, as whoever ran the exam recorded it
     * on the ORDER (policy rule 3, V346): who performed it, the contrast given, the dose. Read
     * from the order every time and shown beside the report, on screen and on paper. It is never
     * part of the read and the server never copies it into one (a report says what its author
     * submitted). Staff only: the patient's copy leaves it out ({@code PatientSafeView}).
     */
    private String examPerformedByName;

    private LocalDateTime examStartedAt;

    private Boolean examContrastAdministered;

    private String examContrastDetails;

    private BigDecimal examRadiationDoseMgy;

    /**
     * QA D2642 / D2643 (r65): what a printed report must say to identify the patient and the
     * request (this field and the six below), read from the order and the patient every time.
     * The patient's copy keeps the hospital, the patient's own date of birth and the prescriber
     * ({@code PatientSafeView}); the clinical question and the sex are staff only.
     */
    private String hospitalName;

    private String hospitalAddress;

    private java.time.LocalDate patientDateOfBirth;

    private String patientGender;

    private String orderingProviderName;

    private String clinicalQuestion;

    private LocalDateTime orderedAt;

    private Integer attachmentsCount;

    private Integer measurementsCount;

    private LocalDateTime lastStatusSyncedAt;

    private LocalDateTime patientNotifiedAt;

    private Boolean patientNotified;

    private Boolean lockedForEditing;

    private String lockReason;

    /**
     * Signing evidence (V132). {@code signed} true with a null
     * {@code signatureAlgorithm} means the row was signed outside this
     * ceremony — externally ingested, or written before V132 — rather than
     * tampered with. The portal renders that distinction rather than hiding it.
     */
    private Boolean signed;

    private String signatureAlgorithm;

    private String signatureValue;

    /** True once the author has declared a critical finding on this read. */
    private Boolean criticalFinding;

    /** True once a clinician has acknowledged that critical finding. */
    private Boolean criticalAcknowledged;

    /**
     * Critical-finding chain state (V133, item 27). {@code criticalNotifiedAt}
     * is when the ordering provider was first told; the level counts escalation
     * rounds fired since. A non-zero level on an unacknowledged finding means
     * the chain has already widened past the ordering provider.
     */
    private LocalDateTime criticalNotifiedAt;

    private LocalDateTime criticalEscalatedAt;

    private Short criticalEscalationLevel;

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
