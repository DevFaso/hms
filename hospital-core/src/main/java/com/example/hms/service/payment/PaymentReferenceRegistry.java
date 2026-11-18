package com.example.hms.service.payment;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * QA D1129 (round 20, the D345 / D975 class): one provider transaction
 * (a mobile-money, card or transfer reference) is recorded once, whatever
 * the till that takes it.
 *
 * <p>The invoice and copay payments refused a reused reference (D345), but
 * the pharmacy checkout and the walk-in sale kept their own ledgers and never
 * asked, so a reference already on an invoice payment, or another patient's
 * dispense, was accepted and the same money was counted twice in the cash
 * closing. Every till goes through {@link #requireUnused} with the normal form
 * of the reference (trimmed, lower case, no spaces), and the check reads all
 * three ledgers: invoice and copay payments (every non-cash method, as D345
 * did), and the mobile-money pharmacy dispense payments and walk-in sales (an
 * insurance authorisation at the pharmacy can cover several dispenses).
 * Billing-money plan PR 8: and the invoice refunds (money out), so a refund
 * never reuses a payment's operator reference, nor a payment a refund's.
 * {@code PaymentReferenceUniquenessGuardTest} keeps every
 * payment write that stores a reference on this path.
 *
 * <p>Scoped to one hospital on purpose (QA D782): a check across hospitals
 * would disclose another tenant's operator transaction ids.
 */
@Component
public class PaymentReferenceRegistry {

    /** The key every till refuses with (in the billing bundles). */
    public static final String DUPLICATE_KEY = "billing.payment.duplicateReference";

    private static final List<PaymentStatus> LIVE_INVOICE_PAYMENTS =
        List.of(PaymentStatus.PENDING, PaymentStatus.CONFIRMED);

    private final PaymentTransactionRepository invoicePayments;
    private final PharmacyPaymentRepository pharmacyPayments;
    private final PharmacySaleRepository pharmacySales;
    /** Billing-money plan PR 8: the refunds paid out by mobile money or transfer carry an operator reference too. */
    private final com.example.hms.repository.InvoiceRefundRepository invoiceRefunds;

    public PaymentReferenceRegistry(PaymentTransactionRepository invoicePayments,
                                    PharmacyPaymentRepository pharmacyPayments,
                                    PharmacySaleRepository pharmacySales,
                                    com.example.hms.repository.InvoiceRefundRepository invoiceRefunds) {
        this.invoicePayments = invoicePayments;
        this.pharmacyPayments = pharmacyPayments;
        this.pharmacySales = pharmacySales;
        this.invoiceRefunds = invoiceRefunds;
    }

    /** The form references are compared in: trimmed, lower case, without spaces; null when blank. */
    public static String normalize(String reference) {
        if (reference == null) {
            return null;
        }
        String compact = reference.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return compact.isEmpty() ? null : compact;
    }

    /**
     * Refuses a reference already recorded on a live payment of this hospital,
     * in any of the three ledgers. A blank reference (cash) or an unknown
     * hospital is not checked.
     *
     * <p>QA D1301 (round 28): the refusal names the request field that carries
     * the reference ({@code field}), so the form marks it; it was a toast with
     * no field. The reason never names the other payment or its patient.</p>
     */
    public void requireUnused(UUID hospitalId, String reference, String field) {
        String normal = normalize(reference);
        if (hospitalId == null || normal == null) {
            return;
        }
        if (invoicePayments.existsLiveReference(hospitalId, normal, LIVE_INVOICE_PAYMENTS)
                || pharmacyPayments.existsLiveReference(hospitalId, normal)
                || pharmacySales.existsLiveReference(hospitalId, normal)
                || invoiceRefunds.existsLiveReference(hospitalId, normal)) {
            throw new FieldBusinessException(field, DUPLICATE_KEY);
        }
    }
}
