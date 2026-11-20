package com.example.hms.payload.dto.pharmacy;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Policy rule 6 (V345): an approval. A third-party cover names the claim or voucher billing checked; an outpatient
 * credit carries the approver's reason and, when they set one, the most that may leave on it (whole francs).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DispenseBillingDeferralDecisionDTO {

    @Size(max = 500)
    @NoCardNumber
    private String note;

    @Size(max = 120)
    @NoCardNumber
    private String coverReference;

    /** Optional ceiling of an outpatient credit, in whole francs. */
    private BigDecimal creditLimit;
}
