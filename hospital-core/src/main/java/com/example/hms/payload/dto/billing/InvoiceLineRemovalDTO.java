package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR 10 review (V309, QA D2703): a line taken off a draft invoice, as the supervisor reads it in « Caisses du jour »
 * and in the day report: the invoice, the line as it read (in the reader's language when the server worded it), its
 * price, who removed it, when and why.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceLineRemovalDTO {
    private UUID id;
    private String invoiceNumber;
    private String line;
    private int quantity;
    private BigDecimal unitPrice;
    /** The price list's price for the line when it had one. */
    private BigDecimal tariffUnitPrice;
    private String reason;
    private String removedByName;
    private LocalDateTime removedAt;
}
