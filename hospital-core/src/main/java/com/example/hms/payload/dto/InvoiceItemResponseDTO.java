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
    /** Existing stored tariff category, for display only; never inferred from description or price. */
    private String coverageCategory;
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

    /**
     * QA D2434: what the payer pays on this line ("Part assureur"), from the coverage applied at issue; 0 when no
     * payer covers it. On a draft (QA D2437) it is the estimate of what the coverage would pay if the invoice were
     * issued now, and {@link #payerShareEstimated} is true.
     */
    private BigDecimal payerShareAmount;

    /** QA D2434: the line total less the payer's part ("Part patient"), before payments. */
    private BigDecimal patientShareAmount;

    /** QA D2437: the payer's part is an estimate (a draft): it is fixed only when the invoice is issued. */
    private boolean payerShareEstimated;

}
