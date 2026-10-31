package com.example.hms.utility;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;
import java.math.BigDecimal;

/**
 * QA D202 / D1104: what a patient still owes on an invoice, the one rule every
 * surface that prints or shows a balance applies (the API response, the
 * invoice PDF the staff and the portal download, the e-mailed copy). A
 * cancelled invoice is owed by nobody, whatever was billed on it: the PDF of
 * a cancelled invoice still said "Reste à payer : 1 500 F CFA" because it
 * computed the balance on its own. Never below zero.
 *
 * <p>{@code InvoiceBalanceGuardTest} fails the build when a rendering class
 * subtracts the amount paid from the total by hand.
 */
public final class InvoiceBalance {

    private InvoiceBalance() {
    }

    public static BigDecimal dueOf(BillingInvoice invoice) {
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        return total.subtract(paid).max(BigDecimal.ZERO);
    }
}
