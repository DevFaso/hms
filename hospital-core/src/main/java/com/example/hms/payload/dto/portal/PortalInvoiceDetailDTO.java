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
    List<PaymentTransactionResponseDTO> payments
) {
}
