package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntiDDecision;
import com.example.hms.enums.ONegativeStatus;
import com.example.hms.enums.OPositiveDecisionBasis;
import com.example.hms.enums.OPositiveReviewPriority;
import com.example.hms.enums.OPositiveRiskTier;
import com.example.hms.enums.OPositiveTierReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** The decision behind one emergency O RhD positive release, and what is still owed on it (V380). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OPositiveReleaseDecisionDTO {

    private UUID id;
    private UUID releaseId;
    private UUID requestId;
    private UUID bloodUnitId;
    private String unitNumber;
    private UUID patientId;
    private String patientName;
    private OPositiveRiskTier riskTier;
    private OPositiveTierReason tierReason;
    private boolean protocolActive;
    private int femaleAgeCutoff;
    private boolean boysLowRisk;
    private int reviewDeadlineHours;
    /** MALE, FEMALE or UNKNOWN, as the record proved it when the bag went out. */
    private String patientSex;
    private Integer patientAgeYears;
    private ONegativeStatus negativeStatus;
    private OPositiveDecisionBasis decisionBasis;
    private String issuedByName;
    /** Who decided; null while the authority is owed. */
    private String decidedByName;
    private String biologistName;
    private boolean verbalOrder;
    private LocalDateTime issuedAt;
    /** Null in the low-risk tier, where no countersignature is owed. */
    private LocalDateTime countersignDueAt;
    private String countersignedByName;
    private LocalDateTime countersignedAt;
    private boolean countersignOverdue;
    private LocalDateTime countersignEscalatedAt;
    private OPositiveReviewPriority reviewPriority;
    private LocalDateTime reviewDueAt;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    /** The permanent flag of the patient rests on this release. */
    private boolean rhdExposure;
    private boolean followUpRequired;
    private LocalDate antibodyScreenDueFrom;
    private LocalDate antibodyScreenDueBy;
    private LocalDateTime antibodyScreenDoneAt;
    private AntiDDecision antiDDecision;
    private String antiDDecidedByName;
    private LocalDateTime antiDDecidedAt;
    private String patientInformedByName;
    private LocalDateTime patientInformedAt;
    private String followUpNote;
}
