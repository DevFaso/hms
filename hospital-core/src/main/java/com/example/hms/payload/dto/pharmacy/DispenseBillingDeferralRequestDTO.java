package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Policy rule 6 (V345): asks for a fill of {@code prescriptionId} to leave the pharmacy « à facturer ». The kind is
 * a third-party cover (billing checks the voucher) or an outpatient credit (finance decides); the other two kinds
 * are never asked for. Never card data.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DispenseBillingDeferralRequestDTO {

    private UUID prescriptionId;

    private DispenseBillingDeferralKind kind;

    @Size(max = 500)
    @NoCardNumber
    private String reason;

    /** The claim or voucher number the patient showed, when there is one. */
    @Size(max = 120)
    @NoCardNumber
    private String coverReference;
}
