package com.example.hms.payload.dto.billing;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Billing-money plan PR 2: a price-list item with the price in force today and the next planned one. The detail read
 * also carries the full {@link #history}; the list leaves it null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceListItemDTO {

    private UUID id;
    private UUID hospitalId;
    /** QA D2071: set in the super-admin's global view only, where rows of several hospitals are listed together. */
    private String hospitalName;
    private String code;
    /** As the hospital typed it, never translated (D-8). */
    private String label;
    private PriceListCategory category;
    private PriceListLinkType linkType;
    private String linkKey;
    /** What the link names, for reading (a lab test's code and name, a service's name); null when unlinked. */
    private String linkLabel;
    private boolean active;
    private LocalDateTime deactivatedAt;
    private String deactivatedByName;
    /** As the admin or accountant typed it. */
    private String deactivationReason;
    private PriceListPriceDTO currentPrice;
    private PriceListPriceDTO nextPrice;
    private LocalDateTime createdAt;
    private String createdByName;
    private LocalDateTime updatedAt;
    private String updatedByName;
    private List<PriceListPriceDTO> history;
}
