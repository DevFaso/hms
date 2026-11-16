package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Billing-money plan PR 2: why a price-list item is deactivated (required, at most 500 characters). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListDeactivateRequestDTO {

    @NotBlank(message = "{billing.priceList.deactivationReasonRequired}")
    @Size(max = 500, message = "{billing.priceList.reasonSize}")
    private String reason;
}
