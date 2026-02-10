package com.example.hms.payload.dto.medicalhistory;

import com.fasterxml.jackson.annotation.JsonFormat;
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
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to create or update immunization record")
public class ImmunizationRequestDTO {

    @NotNull(message = "Patient ID is required")
    @Schema(description = "Patient ID", required = true)
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    @Schema(description = "Hospital ID", required = true)
    private UUID hospitalId;

    @Schema(description = "Staff member who administered")
    private UUID administeredByStaffId;

    @Schema(description = "Encounter ID if given during visit")
    private UUID encounterId;

    // Vaccine Information
    @NotBlank(message = "Vaccine code is required")
    @Size(max = 50)
    @Schema(description = "Vaccine CVX code", required = true)
    private String vaccineCode;

    @NotBlank(message = "Vaccine display is required")
    @Size(max = 255)
    @Schema(description = "Vaccine name", required = true)
    private String vaccineDisplay;

    @Size(max = 100)
    @Schema(description = "Vaccine type")
    private String vaccineType;

    @Size(max = 255)
    @Schema(description = "Target disease")
    private String targetDisease;

    // Administration
    @NotNull(message = "Administration date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Administration date", required = true)
    private LocalDate administrationDate;

    @Schema(description = "Dose number in series")
    private Integer doseNumber;

    @Schema(description = "Total doses in series")
    private Integer totalDosesInSeries;

    @Schema(description = "Dose quantity")
    private Double doseQuantity;

    @Size(max = 50)
    @Schema(description = "Dose unit (mL, mcg)")
    private String doseUnit;

    @Size(max = 100)
    @Schema(description = "Route (IM, SC, PO, intranasal)")
    private String route;

    @Size(max = 100)
    @Schema(description = "Site (left deltoid, right thigh)")
    private String site;

    // Product Details
    @Size(max = 255)
    @Schema(description = "Manufacturer")
    private String manufacturer;

    @Size(max = 100)
    @Schema(description = "Lot number")
    private String lotNumber;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Expiration date")
    private LocalDate expirationDate;

    @Size(max = 50)
    @Schema(description = "NDC code")
    private String ndcCode;

    // Status
    @NotBlank(message = "Status is required")
    @Size(max = 50)
    @Schema(description = "Status", required = true)
    private String status;

    @Size(max = 500)
    @Schema(description = "Status reason")
    private String statusReason;

    @Schema(description = "Verified")
    private Boolean verified;

    @Size(max = 100)
    @Schema(description = "Source of record")
    private String sourceOfRecord;

    // Reaction & Safety
    @Schema(description = "Adverse reaction occurred")
    private Boolean adverseReaction;

    @Size(max = 1024)
    @Schema(description = "Reaction description")
    private String reactionDescription;

    @Size(max = 50)
    @Schema(description = "Reaction severity")
    private String reactionSeverity;

    @Schema(description = "Has contraindication")
    private Boolean contraindication;

    @Size(max = 500)
    @Schema(description = "Contraindication reason")
    private String contraindicationReason;

    // Scheduling
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Next dose due date")
    private LocalDate nextDoseDueDate;

    @Schema(description = "Reminder sent")
    private Boolean reminderSent;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Reminder sent date")
    private LocalDate reminderSentDate;

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

    @Size(max = 2048)
    @Schema(description = "Notes")
    private String notes;

    @Schema(description = "Active record")
    private Boolean active;
}
