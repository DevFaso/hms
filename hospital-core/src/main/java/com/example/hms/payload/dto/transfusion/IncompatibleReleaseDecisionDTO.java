package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.IncompatibleAntibodyStatus;
import com.example.hms.enums.IncompatibleMonitoringCheck;
import com.example.hms.enums.IncompatibleMonitoringOutcome;
import com.example.hms.enums.IncompatibleReleaseBasis;
import com.example.hms.enums.PatientInformedStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One release despite an incompatible crossmatch, with what is still owed on it (V381). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncompatibleReleaseDecisionDTO {

    private UUID id;
    private UUID releaseId;
    private UUID requestId;
    private UUID bloodUnitId;
    private String unitNumber;
    private UUID patientId;
    private String patientName;

    private IncompatibleReleaseBasis decisionBasis;
    /** The release went out on the fallback of the hospital, with no physician reachable. */
    private boolean fallback;
    private int countersignDeadlineHours;
    private String issuedByName;
    private String authoriserName;
    private String declarerRole;
    private String unreachableReason;
    /** BEFORE_ISSUE (a physician named at release), AFTER_ISSUE (countersigned since) or PENDING. */
    private String authorityTiming;

    private IncompatibleAntibodyStatus antibodyStatus;
    private String antibodyDetail;
    private String crossmatchFinding;
    private String recipientAbo;
    private String recipientRh;
    private String unitAbo;
    private String unitRh;
    private boolean rhdMatched;
    private Boolean rhKellMatched;
    private Boolean antigenNegative;
    private boolean leastIncompatible;
    private int candidatesConsidered;
    private String unitChoiceReason;

    private PatientInformedStatus patientInformed;
    private LocalDateTime patientInformedAt;
    private String patientInformedByName;

    private LocalDateTime issuedAt;
    private LocalDateTime countersignDueAt;
    private String countersignedByName;
    private LocalDateTime countersignedAt;
    private boolean countersignOverdue;
    private LocalDateTime countersignEscalatedAt;

    /** The checklist of the rule, in order, and those recorded as done. */
    private List<IncompatibleMonitoringCheck> monitoringChecklist;
    private List<IncompatibleMonitoringCheck> monitoringChecksDone;
    private IncompatibleMonitoringOutcome monitoringOutcome;
    private String monitoringNote;
    private String monitoringRecordedByName;
    private LocalDateTime monitoringRecordedAt;
}
