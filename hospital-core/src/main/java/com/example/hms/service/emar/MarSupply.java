package com.example.hms.service.emar;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.service.pharmacy.FillAccounting;
import com.example.hms.utility.MessageUtil;

import java.math.BigDecimal;

/**
 * QA D3941: what the pharmacy has supplied on a MAR row, as information for the
 * nurse (« Pharmacie : fourni 3/9 », « Pharmacie : non fourni »). It is read
 * from the order's own pharmacy status and the quantity handed over, and it
 * never decides whether the order is on the MAR: an order stays there while it
 * stands ({@link MarOrderStanding#stands}), until the prescriber stops it, its
 * course ends or its stay ends. A ward order the pharmacy supplied is still to
 * be given: supplying it is not giving it.
 */
public final class MarSupply {

    /** The supply states a MAR row can show. */
    public enum State {
        /** Nothing handed over yet. */
        NOT_SUPPLIED,
        /** Part of the ordered quantity handed over. */
        PARTIALLY_SUPPLIED,
        /** The ordered quantity handed over. */
        SUPPLIED,
        /** The pharmacy has none in stock and is waiting for a restock. */
        OUT_OF_STOCK,
        /** The pharmacy asked the prescriber a question about the order. */
        PHARMACY_QUERY,
        /** Supplied outside the hospital's pharmacy: a partner pharmacy, or an ordonnance printed for the family. */
        EXTERNAL
    }

    /**
     * One row's supply.
     *
     * @param state    the state
     * @param supplied the quantity handed over by the hospital's pharmacy (zero when none)
     * @param ordered  the quantity the order asks for, or null when it states none
     * @param label    the state in the reader's language
     */
    public record Reading(State state, BigDecimal supplied, BigDecimal ordered, String label) {
    }

    private MarSupply() {
    }

    /** The supply reading of {@code rx}, given what was handed over on it ({@code null} when nothing). */
    public static Reading of(Prescription rx, BigDecimal handedOver) {
        BigDecimal supplied = handedOver == null || handedOver.signum() < 0 ? BigDecimal.ZERO : handedOver;
        BigDecimal ordered = rx == null ? null : FillAccounting.expectedLifetimeQuantity(rx);
        if (ordered != null && ordered.signum() <= 0) {
            ordered = null;
        }
        State state = stateOf(rx == null ? null : rx.getStatus(), supplied, ordered);
        return new Reading(state, supplied, ordered, labelOf(state, supplied, ordered));
    }

    static State stateOf(PrescriptionStatus status, BigDecimal supplied, BigDecimal ordered) {
        if (status == PrescriptionStatus.PENDING_CLARIFICATION) {
            return State.PHARMACY_QUERY;
        }
        if (status == PrescriptionStatus.SENT_TO_PARTNER || status == PrescriptionStatus.PARTNER_ACCEPTED
                || status == PrescriptionStatus.PARTNER_DISPENSED || status == PrescriptionStatus.PRINTED_FOR_PATIENT) {
            return State.EXTERNAL;
        }
        // The pharmacy's own word on the order first: its status is computed from the same fills.
        if (status == PrescriptionStatus.DISPENSED) {
            return State.SUPPLIED;
        }
        if (supplied.signum() > 0) {
            return ordered != null && supplied.compareTo(ordered) >= 0 ? State.SUPPLIED : State.PARTIALLY_SUPPLIED;
        }
        if (status == PrescriptionStatus.PARTIALLY_FILLED) {
            return State.PARTIALLY_SUPPLIED;
        }
        return status == PrescriptionStatus.PENDING_STOCK ? State.OUT_OF_STOCK : State.NOT_SUPPLIED;
    }

    private static String labelOf(State state, BigDecimal supplied, BigDecimal ordered) {
        return switch (state) {
            case PARTIALLY_SUPPLIED, SUPPLIED -> quantityLabel(state, supplied, ordered);
            case OUT_OF_STOCK -> MessageUtil.resolve("nurse.mar.supply.outOfStock");
            case PHARMACY_QUERY -> MessageUtil.resolve("nurse.mar.supply.pharmacyQuery");
            case EXTERNAL -> MessageUtil.resolve("nurse.mar.supply.external");
            case NOT_SUPPLIED -> MessageUtil.resolve("nurse.mar.supply.notSupplied");
        };
    }

    /** « fourni 3/9 », or without a quantity on record what the pharmacy's status says. */
    private static String quantityLabel(State state, BigDecimal supplied, BigDecimal ordered) {
        if (supplied.signum() <= 0) {
            return MessageUtil.resolve(state == State.SUPPLIED ? "nurse.mar.supply.whole" : "nurse.mar.supply.partly");
        }
        return ordered != null
            ? MessageUtil.resolve("nurse.mar.supply.suppliedOf", plain(supplied), plain(ordered))
            : MessageUtil.resolve("nurse.mar.supply.supplied", plain(supplied));
    }

    private static String plain(BigDecimal quantity) {
        return com.example.hms.utility.DisplayNumbers.quantity(quantity);
    }
}
