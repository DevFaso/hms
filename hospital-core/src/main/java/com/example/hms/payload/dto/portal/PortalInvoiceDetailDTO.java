package com.example.hms.payload.dto.portal;

import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import com.example.hms.payload.dto.PaymentTransactionResponseDTO;

import java.util.List;

/**
 * QA D982: one of the patient's invoices as the cashier sees it: its lines
 * and every payment with its state (declared, confirmed, rejected, reversed),
 * so the portal card is not only two totals. Staff notes on a payment row are
 * left out.
 */
public record PortalInvoiceDetailDTO(
    BillingInvoiceResponseDTO invoice,
    List<InvoiceItemResponseDTO> items,
    List<PaymentTransactionResponseDTO> payments,
    /** QA D974: what was discounted, waived or credited (type and amount; the clerk's reason stays with billing). */
    List<com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO> adjustments,
    /** Billing-money plan PR 8 (QA D1900): the refunds handed back to the patient (paid out only). */
    List<PortalRefundDTO> refunds
) {
    /** The detail without refunds (an invoice with none, or a caller that does not read them). */
    public PortalInvoiceDetailDTO(BillingInvoiceResponseDTO invoice, List<InvoiceItemResponseDTO> items,
                                  List<PaymentTransactionResponseDTO> payments,
                                  List<com.example.hms.payload.dto.InvoiceAdjustmentResponseDTO> adjustments) {
        this(invoice, items, payments, adjustments, List.of());
    }
}
