package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Policy rules 1 and 6 (provisional, V345): what a hospital admin may change of the hospital's billing policy. A
 * field left out keeps its value. The pharmacist-renewal flag is accepted only as false: the service refuses true.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class HospitalBillingPolicyUpdateDTO {

    /** Rule 6: a consultation line by default on planned, day-case, newborn, labour and transfer admissions. */
    private Boolean autoConsultationOnPlannedAdmission;

    /** Rule 1: « prolongation exceptionnelle par le pharmacien ». Off; true is refused. */
    private Boolean pharmacistExceptionalRenewal;
}
