package com.example.hms.service.payment;

import com.example.hms.exception.BusinessException;
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
 * insurance authorisation at the pharmacy can cover several dispenses). {@code PaymentReferenceUniquenessGuardTest} keeps every
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

    public PaymentReferenceRegistry(PaymentTransactionRepository invoicePayments,
                                    PharmacyPaymentRepository pharmacyPayments,
                                    PharmacySaleRepository pharmacySales) {
        this.invoicePayments = invoicePayments;
        this.pharmacyPayments = pharmacyPayments;
        this.pharmacySales = pharmacySales;
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
     */
    public void requireUnused(UUID hospitalId, String reference) {
        String normal = normalize(reference);
        if (hospitalId == null || normal == null) {
            return;
        }
        if (invoicePayments.existsLiveReference(hospitalId, normal, LIVE_INVOICE_PAYMENTS)
                || pharmacyPayments.existsLiveReference(hospitalId, normal)
                || pharmacySales.existsLiveReference(hospitalId, normal)) {
            throw new BusinessException(DUPLICATE_KEY);
        }
    }
}
