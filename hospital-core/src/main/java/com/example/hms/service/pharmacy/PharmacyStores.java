package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyType;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.pharmacy.Pharmacy;

/**
 * QA D4182 (r81): which rows of the pharmacy registry are the hospital's own stores. The registry also holds the
 * partner and community pharmacies an order can be sent to; they are other businesses, reached by stock-out
 * routing or SMS dispatch. Only a {@link PharmacyType#HOSPITAL_DISPENSARY} holds the hospital's stock: it alone is
 * offered in a store picker, receives a transfer or a goods receipt, gets an inventory item, sells at the counter
 * and is reconciled. A partner used to be offered everywhere, and hospital stock could be moved onto its books.
 */
public final class PharmacyStores {

    /** The refusal's message key; its argument is the pharmacy's name. */
    public static final String NOT_OWN_STORE_KEY = "pharmacy.store.notOwn";

    private PharmacyStores() {
    }

    /** Whether {@code pharmacy} is one of its hospital's own stores. */
    public static boolean isOwnStore(Pharmacy pharmacy) {
        return pharmacy != null && pharmacy.getPharmacyType() == PharmacyType.HOSPITAL_DISPENSARY;
    }

    /** Refuses a stock or till operation at a pharmacy that is not one of the hospital's own stores. */
    public static void requireOwnStore(Pharmacy pharmacy, String field) {
        if (!isOwnStore(pharmacy)) {
            throw new FieldBusinessException(field, NOT_OWN_STORE_KEY,
                    pharmacy == null || pharmacy.getName() == null ? "—" : pharmacy.getName());
        }
    }
}
