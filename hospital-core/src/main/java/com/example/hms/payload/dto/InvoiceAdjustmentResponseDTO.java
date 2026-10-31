package com.example.hms.payload.dto;

import com.example.hms.model.InvoiceAdjustment;

import java.math.BigDecimal;

/** QA D974: one discount, waiver or credit note of an invoice. */
public record InvoiceAdjustmentResponseDTO(
    String id,
    String type,
    BigDecimal amount,
    String reason,
    String invoiceItemId,
    String invoiceItemDescription,
    String createdAt
) {
    public static InvoiceAdjustmentResponseDTO from(InvoiceAdjustment row) {
        return new InvoiceAdjustmentResponseDTO(
            row.getId() != null ? row.getId().toString() : null,
            row.getType() != null ? row.getType().name() : null,
            row.getAmount(),
            row.getReason(),
            row.getInvoiceItem() != null && row.getInvoiceItem().getId() != null
                ? row.getInvoiceItem().getId().toString() : null,
            row.getInvoiceItem() != null ? row.getInvoiceItem().getItemDescription() : null,
            row.getCreatedAt() != null ? row.getCreatedAt().toString() : null);
    }
}
