package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import com.example.hms.enums.ImagingOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Detailed imaging order payload returned to clients.")
public class ImagingOrderResponseDTO {

    private UUID id;

    private UUID patientId;

    private String patientDisplayName;

    private String patientMrn;

    private UUID hospitalId;

    private String hospitalName;

    private ImagingModality modality;

    private String studyType;

    /** QA D3450 / D3254 (V361): the imaging catalogue exam the order was picked from; null for a free-text order. */
    private UUID catalogueItemId;

    /** The catalogue exam's code when the order was placed. */
    private String catalogueCode;

    private String bodyRegion;

    private ImagingLaterality laterality;

    private ImagingOrderPriority priority;

    private ImagingOrderStatus status;

    private String clinicalQuestion;

    private Boolean contrastRequired;

    private String contrastType;

    private Boolean hasContrastAllergy;

    private String contrastAllergyDetails;

    private Boolean sedationRequired;

    private String sedationType;

    private String sedationNotes;

    private Boolean requiresNpo;

    private Boolean hasImplantedDevice;

    private String implantedDeviceDetails;

    private Boolean requiresPregnancyTest;

    private Boolean needsInterpreter;

    private String additionalProtocols;

    private String specialInstructions;

    private LocalDate scheduledDate;

    private String scheduledTime;

    private String appointmentLocation;

    private Boolean portableStudy;

    private Boolean requiresAuthorization;

    private String authorizationNumber;

    private LocalDateTime orderedAt;

    private String orderingProviderName;

    private String orderingProviderNpi;

    private UUID orderingProviderUserId;
    /**
     * QA D1081 (D995 class): the user the server's author check compares the caller with, so a
     * list offers the author-only actions (cancel, edit, sign) on this row only to its author.
     */
    private java.util.UUID authorUserId;

    private LocalDateTime providerSignedAt;

    private String providerSignatureStatement;

    private UUID encounterId;

    private Boolean duplicateOfRecentOrder;

    private UUID duplicateReferenceOrderId;

    private List<ImagingOrderDuplicateMatchDTO> duplicateMatches;

    /** Provisional rule 2 (V297): placed despite the duplicate warning, with its reason. */
    private Boolean duplicateOverride;

    private com.example.hms.enums.ImagingDuplicateOverrideReason duplicateOverrideReason;

    private String duplicateOverrideNote;

    private String duplicateOverrideByName;

    private LocalDateTime duplicateOverrideAt;

    /** Provisional rule 1 (V297): the study was done without its required pre-authorisation number. */
    private Boolean authorizationPending;

    private LocalDateTime authorizationPendingSince;

    private LocalDateTime cancelledAt;

    /** When the study was completed (V294); null while it is not. */
    private LocalDateTime completedAt;

    // ── The exam record (policy rule 3, V346) ──
    private LocalDateTime checkedInAt;

    private String checkedInByName;

    private LocalDateTime examStartedAt;

    private UUID performedByUserId;

    private String performedByName;

    private String examTechnicalNote;

    private Boolean examContrastAdministered;

    private String examContrastDetails;

    private java.math.BigDecimal examRadiationDoseMgy;

    private LocalDateTime examRecordedAt;

    /**
     * Whether an imaging technician of the order's hospital may perform this study: false for an
     * ultrasound where the hospital keeps ultrasound with physicians (the default).
     */
    private Boolean technicianMayPerform;

    private String cancellationReason;

    private String cancelledByName;

    private String workflowNotes;

    private Boolean requiresFollowUpCall;

    private LocalDateTime updatedAt;
}
