package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Unified medication timeline entry combining prescriptions and pharmacy fills.
 * Represents a single medication in the patient's timeline with overlap detection metadata.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationTimelineEntryDTO {

    // Entry identification
    private String entryId; // "RX-{uuid}" or "FILL-{uuid}"
    private String entryType; // "PRESCRIPTION" or "PHARMACY_FILL"

    // Medication
    private String medicationName;
    private String medicationCode; // NDC or RxNorm
    private String strength;
    private String dosageForm;

    // Timing
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate; // prescription startDate or fill fillDate

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate; // calculated or explicit

    private Integer daysSupply;
    private String duration; // from prescription

    // Dosing
    private String dosage;
    private String frequency;
    private String route;
    private BigDecimal quantityDispensed;
    private String quantityUnit;

    // Source
    private String source; // "Internal Prescription", "Retail Pharmacy", "Mail Order", etc.
    private String prescriberName;
    private String pharmacyName;

    // Status
    private String status; // ACTIVE, COMPLETED, DISCONTINUED, etc.
    private boolean controlledSubstance;

    // Overlap detection
    private boolean hasOverlap;
    private List<String> overlappingWith; // List of other entryIds that overlap with this one

    /**
     * Number of days this medication overlaps with another medication of the same or similar type.
     */
    private Integer overlapDays;

    // Clinical flags
    private boolean hasInteraction;
    private List<String> interactingWith; // List of medication names that interact

    // Metadata
    private UUID prescriptionId;
    private UUID pharmacyFillId;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime documentedAt;
}
