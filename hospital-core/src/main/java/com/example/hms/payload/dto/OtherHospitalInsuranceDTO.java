package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Owner decision 2026-11-22: a patient's insurance POLICY belongs to the patient; the hospital's AGREEMENT with the
 * insurer belongs to each hospital. Hospital B's desk sees a policy recorded at another hospital, « à vérifier », by
 * its identity only - the fields below and nothing else. Never that hospital's claims, amounts, ceilings used,
 * approvals, pre-authorisations, invoices, coverage or eligibility notes, uploaded documents, its plan or payer ids,
 * its verification, or which of its staff entered it. {@code OtherHospitalInsuranceDtoGuardTest} pins the field list.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A policy recorded at another hospital, shown to verify: its identity only")
public class OtherHospitalInsuranceDTO {

    @Schema(description = "The source row, named only to reuse it (it is never readable or writable here by id)")
    private UUID sourceInsuranceId;

    @Schema(description = "The hospital where the policy was recorded (its name only)")
    private String sourceHospitalName;

    @Schema(description = "The insurer as recorded", example = "CNAMGS")
    private String insurerName;

    @Schema(description = "The member / policy number as recorded")
    private String policyNumber;

    @Schema(description = "The plan (formule) name as recorded, when the source hospital named one")
    private String planName;

    @Schema(description = "The holder's relationship to the patient as recorded", example = "Self")
    private String holderRelationship;

    @Schema(description = "Valid from, as recorded")
    private LocalDate effectiveDate;

    @Schema(description = "Valid to, as recorded")
    private LocalDate expirationDate;

    @Schema(description = "The day the source hospital recorded it (« Déclarée à … le … »): a declaration, never a "
        + "verification - the source hospital's verification is not shown")
    private LocalDate declaredOn;

    @Schema(description = "Whether THIS hospital has an active agreement (payer) with that insurer: without one a "
        + "reuse makes an identity-only record that bills no payer share")
    private boolean agreementHere;
}
