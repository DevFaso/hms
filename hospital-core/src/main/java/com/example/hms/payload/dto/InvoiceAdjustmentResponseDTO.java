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
    String createdAt,
    /** QA D1105: when the adjustment was reversed (ISO, server time); null while it stands. */
    String reversedAt,
    /** QA D1105: why it was reversed (billing staff only; null for the patient). */
    String reversalReason
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
            row.getCreatedAt() != null ? row.getCreatedAt().toString() : null,
            row.getReversedAt() != null ? row.getReversedAt().toString() : null,
            row.getReversalReason());
    }
}
