package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;


@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InvoiceItemResponseDTO {

    private UUID id;

    private UUID billingInvoiceId;

    private String itemDescription;

    private String itemCategory;
    private String relatedServiceName;

    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String invoiceNumber;

    private UUID assignmentId;
    private String staffDisplay;

    private UUID relatedServiceId;

    /** Billing-money plan PR 3: where the unit price came from (TARIFF, SERVICE_NAME, MANUAL, UNPRICED, LEGACY). */
    private String priceSource;

    /** The price list's price for this line ("Prix de la grille"): the tariff, overridden or not; null otherwise. */
    private BigDecimal tariffUnitPrice;

    /** Why the tariff was overridden ("Prix modifié — motif"), as typed; null when it was not. */
    private String overrideReason;

}
