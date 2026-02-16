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

    private LocalDateTime providerSignedAt;

    private String providerSignatureStatement;

    private UUID encounterId;

    private Boolean duplicateOfRecentOrder;

    private UUID duplicateReferenceOrderId;

    private List<ImagingOrderDuplicateMatchDTO> duplicateMatches;

    private LocalDateTime cancelledAt;

    private String cancellationReason;

    private String cancelledByName;

    private String workflowNotes;

    private Boolean requiresFollowUpCall;

    private LocalDateTime updatedAt;
}
