package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 2: the editable part of a price-list item. The code never changes, and prices change only
 * by adding a version ({@link PriceListPriceRequestDTO}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListItemUpdateRequestDTO {

    @NotBlank(message = "{billing.priceList.labelRequired}")
    @Size(max = 255, message = "{billing.priceList.labelSize}")
    private String label;
    @NotNull(message = "{billing.priceList.categoryRequired}")
    private PriceListCategory category;
    private PriceListLinkType linkType;
    @Size(max = 255, message = "{billing.priceList.linkKeySize}")
    private String linkKey;
}
