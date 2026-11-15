package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
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

    @Size(max = 255)
    private String label;
    private PriceListCategory category;
    private PriceListLinkType linkType;
    @Size(max = 255)
    private String linkKey;
}
