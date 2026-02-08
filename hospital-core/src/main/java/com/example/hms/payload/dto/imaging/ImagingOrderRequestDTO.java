package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingLaterality;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload for placing a generalized imaging order (XR, CT, MRI, etc.)")
public class ImagingOrderRequestDTO {

    @NotNull
    @Schema(description = "Patient identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotNull
    @Schema(description = "Hospital identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @Schema(description = "Encounter identifier when the order originates from an active visit")
    private UUID encounterId;

    @NotNull
    @Schema(description = "Imaging modality", example = "CT", requiredMode = Schema.RequiredMode.REQUIRED)
    private ImagingModality modality;

    @NotBlank
    @Size(max = 150)
    @Schema(description = "Specific study or protocol name", example = "CT Chest with contrast")
    private String studyType;

    @Size(max = 150)
    @Schema(description = "Body region or anatomy of focus", example = "Chest")
    private String bodyRegion;

    @NotNull
    @Schema(description = "Requested priority", example = "ROUTINE", requiredMode = Schema.RequiredMode.REQUIRED)
    private ImagingOrderPriority priority;

    @Schema(description = "Laterality details if applicable")
    private ImagingLaterality laterality;

    @Size(max = 2000)
    @Schema(description = "Clinical question or reason for exam")
    private String clinicalQuestion;

    @Schema(description = "Whether contrast is required for the study")
    private Boolean contrastRequired;

    @Size(max = 120)
    @Schema(description = "Preferred contrast type or protocol", example = "IV iodinated")
    private String contrastType;

    @Schema(description = "Flag if the patient has a documented contrast allergy")
    private Boolean hasContrastAllergy;

    @Size(max = 500)
    @Schema(description = "Details about the contrast allergy or mitigation steps")
    private String contrastAllergyDetails;

    @Schema(description = "Indicates if sedation is required")
    private Boolean sedationRequired;

    @Size(max = 120)
    @Schema(description = "Sedation type preference", example = "Moderate conscious sedation")
    private String sedationType;

    @Size(max = 500)
    @Schema(description = "Sedation notes or requirements")
    private String sedationNotes;

    @Schema(description = "Whether patient must be NPO before the study")
    private Boolean requiresNpo;

    @Schema(description = "Flag if patient has implanted devices impacting imaging")
    private Boolean hasImplantedDevice;

    @Size(max = 500)
    @Schema(description = "Details about implanted devices (pacemaker, pumps, etc.)")
    private String implantedDeviceDetails;

    @Schema(description = "Whether a pregnancy test is required before imaging")
    private Boolean requiresPregnancyTest;

    @Schema(description = "Flag if an interpreter is required during the visit")
    private Boolean needsInterpreter;

    @Size(max = 1000)
    @Schema(description = "Additional protocols, reformats, or measurements requested")
    private String additionalProtocols;

    @Size(max = 1000)
    @Schema(description = "Special handling or prep instructions")
    private String specialInstructions;

    @Schema(description = "Requested appointment date")
    private LocalDate scheduledDate;

    @Size(max = 50)
    @Schema(description = "Requested appointment time window", example = "09:30")
    private String scheduledTime;

    @Size(max = 255)
    @Schema(description = "Preferred imaging location or department")
    private String appointmentLocation;

    @Schema(description = "Indicates if exam should be portable / bedside")
    private Boolean portableStudy;

    @Schema(description = "Flag if prior authorization is required")
    private Boolean requiresAuthorization;

    @Size(max = 120)
    @Schema(description = "Authorization number when already secured")
    private String authorizationNumber;

    @Size(max = 200)
    @Schema(description = "Ordering provider display name")
    private String orderingProviderName;

    @Size(max = 40)
    @Schema(description = "Ordering provider NPI or license")
    private String orderingProviderNpi;

    @Schema(description = "Persist order as draft instead of submitting")
    private Boolean saveAsDraft;

    @Schema(description = "Lookback window (days) for duplicate check; defaults to 30")
    private Integer duplicateLookbackDays;

    @Size(max = 1000)
    @Schema(description = "Workflow or scheduling notes not surfaced to patient")
    private String workflowNotes;
}
