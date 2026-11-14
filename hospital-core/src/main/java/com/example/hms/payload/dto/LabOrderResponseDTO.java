package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabOrderResponseDTO {

    private String id;
    private String labOrderCode;
    private String patientId;
    private String patientFullName;
    private String patientEmail;
    /** Lab requests drawer: the patient's MRN at the order's hospital (null when unregistered there). */
    private String patientMrn;
    /** Lab requests drawer: the patient's date of birth, from which the drawer shows the age. */
    private java.time.LocalDate patientDateOfBirth;
    /** Lab requests list and drawer: the clinician who placed the order. */
    private String orderingStaffName;
    private String hospitalId;
    private String hospitalName;
    /** Null when the ordering hospital performs the test itself. */
    private String performingHospitalId;
    private String performingHospitalName;
    private String labTestName;

    /** QA D648: the order's test definition no longer exists; labTestName is the "deleted test" label. */
    private boolean labTestDefinitionMissing;
    private String labTestCode;
    /** QA D1095: the test definition's unit, which a result is entered in. */
    private String labTestUnit;
    private LocalDateTime orderDatetime;
    private String status;
    private String priority;
    private String clinicalIndication;
    private String medicalNecessityNote;
    private String notes;

    /** QA D673: why the order was cancelled (null unless CANCELLED). */
    private String cancellationReason;
    private String primaryDiagnosisCode;
    /**
     * Lab requests drawer: the label the patient's own diagnosis record gives
     * {@link #primaryDiagnosisCode} at the order's hospital ("Polyurie" for R35).
     * Resolved on the single-order read only; null when no record names the code.
     */
    private String primaryDiagnosisLabel;
    @Builder.Default
    private List<String> additionalDiagnosisCodes = new ArrayList<>();
    private String orderChannel;
    private String orderChannelOther;
    private boolean documentationSharedWithLab;
    private String documentationReference;
    private String orderingProviderNpi;
    private String providerSignatureDigest;
    private LocalDateTime signedAt;
    private String signedByUserId;
    private boolean standingOrder;
    private LocalDateTime standingOrderExpiresAt;
    private LocalDateTime standingOrderLastReviewedAt;
    private LocalDateTime standingOrderReviewDueAt;
    private Integer standingOrderReviewIntervalDays;
    private String standingOrderReviewNotes;
    /**
     * The duplicate-order warning (shape of the imaging order's): set on the
     * create response when the same patient already had an active order of
     * the same test at this hospital within {@code LabOrderDuplicateCheck.WINDOW}.
     * The order is placed either way; a warning never blocks.
     */
    private Boolean duplicateOfRecentOrder;
    private java.util.UUID duplicateReferenceOrderId;
    @Builder.Default
    private List<LabOrderDuplicateMatchDTO> duplicateMatches = new ArrayList<>();
    /** Whether the orderer acknowledged the warning ("Order anyway") before placing it. */
    private Boolean duplicateAcknowledged;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

