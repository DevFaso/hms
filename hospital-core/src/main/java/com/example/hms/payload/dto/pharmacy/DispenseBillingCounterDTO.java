package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Policy rule 6 (V345): what the counter reads before a fill of a prescription leaves with money due. An inpatient
 * item goes on the account of the open stay and needs nothing; otherwise the open deferrals of the prescription
 * (asked for, or approved by somebody else).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DispenseBillingCounterDTO {
    private boolean onOpenStay;
    private List<DispenseBillingDeferralDTO> open;
}
