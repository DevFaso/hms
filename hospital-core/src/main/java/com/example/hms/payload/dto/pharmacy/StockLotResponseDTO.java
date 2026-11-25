package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockLotResponseDTO {

    private UUID id;
    private UUID inventoryItemId;
    private String lotNumber;
    /** QA D1163: the stock item's unit, so the fill form offers only lots of the prescribed form. */
    private String unit;
    private LocalDate expiryDate;
    private BigDecimal initialQuantity;
    private BigDecimal remainingQuantity;
    private String supplier;
    private BigDecimal unitCost;
    private LocalDate receivedDate;
    private UUID receivedBy;
    private String notes;

    /**
     * Scannable identifier printed on the lot label (V138). Null on lots
     * received before the migration — printing a label backfills it.
     */
    private String barcodeValue;

    /*
     * QA D3940 (r77): what the dispensing lot picker shows of each lot's product, filled by the dispensable-lots
     * read only. The picker showed the lot number and the expiry, never the product or its strength, and
     * preselected a 250 mg lot for a 500 mg order.
     */
    /** The lot's product in the reader's language, with its strength. */
    private String productName;
    /** The product's strength as written ("500 mg"), when the catalogue states one. */
    private String strength;
    /** The product's dosage form as the catalogue records it. */
    private String form;
    /** PRESCRIBED, EQUIVALENT or SUBSTITUTE ({@code DispensedProductMatch}); a substitute needs a recorded reason. */
    private String productMatch;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
