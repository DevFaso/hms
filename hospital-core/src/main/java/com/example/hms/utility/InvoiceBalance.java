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
 * computed the balance on its own. Never below zero. Billing-money plan PR 7:
 * what the patient owes is their share ({@link #patientShareOf}), not the total.
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
        return patientShareOf(invoice).subtract(keptOf(invoice)).max(BigDecimal.ZERO);
    }

    /**
     * Billing-money plan PR 7: what the payers owe on the invoice (its live shares; a share the payer rejected is no
     * longer counted, so it moves back to the patient).
     */
    public static BigDecimal payerShareOf(BillingInvoice invoice) {
        if (invoice == null || invoice.getPayerShareTotal() == null) {
            return BigDecimal.ZERO;
        }
        return invoice.getPayerShareTotal().max(BigDecimal.ZERO);
    }

    /**
     * Billing-money plan PR 7: the patient's share of the bill, before anything paid: the total (after discounts,
     * waivers and credit notes, owner default D-6: they lower the patient's share) less the payers' share. Never
     * below zero. Every rule that compares the money held with the bill (payable, settled, refund due, a copay that
     * fits) compares it with this, never with the total ({@code PatientShareBalanceGuardTest}).
     */
    public static BigDecimal patientShareOf(BillingInvoice invoice) {
        if (invoice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
        return total.subtract(payerShareOf(invoice)).max(BigDecimal.ZERO);
    }

    /**
     * Billing-money plan PR 7: the status that follows the money on an issued invoice: PAID once the hospital holds
     * the patient's share, PARTIALLY_PAID while it holds part of it, SENT while it holds nothing. A draft and a
     * cancelled invoice keep their status.
     */
    public static InvoiceStatus settledStatusOf(BillingInvoice invoice) {
        InvoiceStatus current = invoice.getStatus();
        if (current == InvoiceStatus.DRAFT || current == InvoiceStatus.CANCELLED) {
            return current;
        }
        BigDecimal kept = keptOf(invoice);
        if (kept.compareTo(patientShareOf(invoice)) >= 0) {
            return InvoiceStatus.PAID;
        }
        return kept.signum() > 0 ? InvoiceStatus.PARTIALLY_PAID : InvoiceStatus.SENT;
    }

    /**
     * Billing-money plan PR 8: the money the hospital still holds on the invoice: every franc received less what
     * was handed back on refunds paid out. Never below zero.
     */
    public static BigDecimal keptOf(BillingInvoice invoice) {
        if (invoice == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal refunded = invoice.getAmountRefunded() != null ? invoice.getAmountRefunded() : BigDecimal.ZERO;
        return paid.subtract(refunded).max(BigDecimal.ZERO);
    }

    /**
     * QA D1466: what the hospital holds above the bill and owes back. A check-in
     * copay above the visit's total folds whole onto the visit invoice (the money
     * was received, its row moves unsplit), so the amount paid may pass the total.
     * Nothing further is owed, and this is the refund due, less what refunds
     * already handed back (billing-money plan PR 8, QA D1900). Nothing on a
     * cancelled invoice.
     */
    public static BigDecimal refundDueOf(BillingInvoice invoice) {
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        // PR 7: above the patient's share, not the total: the payers' share is never the patient's to pay.
        return keptOf(invoice).subtract(patientShareOf(invoice)).max(BigDecimal.ZERO);
    }

    /**
     * Billing-money plan PR 8: what a new refund request may still ask for: the refund due less the refunds already
     * asked for or approved and not yet paid ({@code openRefunds}). Never below zero.
     */
    public static BigDecimal refundableOf(BillingInvoice invoice, BigDecimal openRefunds) {
        BigDecimal open = openRefunds != null ? openRefunds : BigDecimal.ZERO;
        return refundDueOf(invoice).subtract(open).max(BigDecimal.ZERO);
    }
}
