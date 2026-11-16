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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Billing-money plan PR 2: a new price-list item with its first price. Checked by {@code PriceListService}, which
 * names every refused field at once (code, label, category, link, unit price, effective date).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListItemRequestDTO {

    @NotBlank(message = "{billing.priceList.codeRequired}")
    @Size(max = 40, message = "{billing.priceList.codeSize}")
    private String code;
    @NotBlank(message = "{billing.priceList.labelRequired}")
    @Size(max = 255, message = "{billing.priceList.labelSize}")
    private String label;
    @NotNull(message = "{billing.priceList.categoryRequired}")
    private PriceListCategory category;
    /** Optional; with {@link #linkKey}, or neither. */
    private PriceListLinkType linkType;
    @Size(max = 255, message = "{billing.priceList.linkKeySize}")
    private String linkKey;
    /** Whole francs, at least 0. */
    @NotNull(message = "{billing.priceList.priceRequired}")
    private BigDecimal unitPrice;
    /** Today when omitted; never in the past. */
    private LocalDate effectiveFrom;
    /** Optional on the first price. */
    @Size(max = 500, message = "{billing.priceList.reasonSize}")
    private String changeReason;
}
