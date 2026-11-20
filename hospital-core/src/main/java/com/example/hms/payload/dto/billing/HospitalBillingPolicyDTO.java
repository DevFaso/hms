package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Policy rules 1 and 6 (provisional, V345): a hospital's billing and dispensing choices, read and written whole.
 * {@code canEdit} tells the screen whether the reader is a hospital admin there. Both rules are provisional until
 * the hospital's director and finance manager (rule 6) and the pharmacists' order (rule 1) sign them off.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HospitalBillingPolicyDTO {

    /** Rule 6: a consultation line by default on planned, day-case, newborn, labour and transfer admissions. */
    private Boolean autoConsultationOnPlannedAdmission;

    /** Rule 1: « prolongation exceptionnelle par le pharmacien ». Always false: the service refuses true. */
    private Boolean pharmacistExceptionalRenewal;

    private LocalDateTime updatedAt;

    private boolean canEdit;
}
