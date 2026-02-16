package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Immunization record details")
public class ImmunizationResponseDTO {

    @Schema(description = "Unique identifier")
    private UUID id;

    @Schema(description = "Patient ID")
    private UUID patientId;

    @Schema(description = "Patient name")
    private String patientName;

    @Schema(description = "Hospital ID")
    private UUID hospitalId;

    @Schema(description = "Hospital name")
    private String hospitalName;

    @Schema(description = "Administered by staff ID")
    private UUID administeredByStaffId;

    @Schema(description = "Administrator name")
    private String administeredByName;

    @Schema(description = "Encounter ID")
    private UUID encounterId;

    // Vaccine Information
    @Schema(description = "Vaccine code")
    private String vaccineCode;

    @Schema(description = "Vaccine display")
    private String vaccineDisplay;

    @Schema(description = "Vaccine type")
    private String vaccineType;

    @Schema(description = "Target disease")
    private String targetDisease;

    // Administration
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Administration date")
    private LocalDate administrationDate;

    @Schema(description = "Dose number")
    private Integer doseNumber;

    @Schema(description = "Total doses")
    private Integer totalDosesInSeries;

    @Schema(description = "Dose quantity")
    private Double doseQuantity;

    @Schema(description = "Dose unit")
    private String doseUnit;

    @Schema(description = "Route")
    private String route;

    @Schema(description = "Site")
    private String site;

    // Product Details
    @Schema(description = "Manufacturer")
    private String manufacturer;

    @Schema(description = "Lot number")
    private String lotNumber;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Expiration date")
    private LocalDate expirationDate;

    @Schema(description = "NDC code")
    private String ndcCode;

    // Status
    @Schema(description = "Status")
    private String status;

    @Schema(description = "Status reason")
    private String statusReason;

    @Schema(description = "Verified")
    private Boolean verified;

    @Schema(description = "Source of record")
    private String sourceOfRecord;

    // Reaction
    @Schema(description = "Adverse reaction")
    private Boolean adverseReaction;

    @Schema(description = "Reaction description")
    private String reactionDescription;

    @Schema(description = "Reaction severity")
    private String reactionSeverity;

    @Schema(description = "Contraindication")
    private Boolean contraindication;

    @Schema(description = "Contraindication reason")
    private String contraindicationReason;

    // Scheduling
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Next dose due")
    private LocalDate nextDoseDueDate;

    @Schema(description = "Reminder sent")
    private Boolean reminderSent;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Reminder sent date")
    private LocalDate reminderSentDate;

    @Schema(description = "Overdue")
    private Boolean overdue;

    // Clinical Significance
    @Schema(description = "Required for school")
    private Boolean requiredForSchool;

    @Schema(description = "Required for travel")
    private Boolean requiredForTravel;

    @Schema(description = "Occupational requirement")
    private Boolean occupationalRequirement;

    @Schema(description = "Pregnancy related")
    private Boolean pregnancyRelated;

    // Documentation
    @Schema(description = "VIS given")
    private Boolean visGiven;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "VIS date")
    private LocalDate visDate;

    @Schema(description = "Consent obtained")
    private Boolean consentObtained;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Consent date")
    private LocalDate consentDate;

    @Schema(description = "Insurance reported")
    private Boolean insuranceReported;

    @Schema(description = "Registry reported")
    private Boolean registryReported;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Registry reported date")
    private LocalDate registryReportedDate;

    @Schema(description = "Notes")
    private String notes;

    @Schema(description = "Active")
    private Boolean active;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Created timestamp")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    @Schema(description = "Updated timestamp")
    private LocalDateTime updatedAt;
}
