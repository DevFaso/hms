package com.example.hms.enums;

public enum PrescriptionStatus {
    DRAFT,
    PENDING_SIGNATURE,
    SIGNED,
    TRANSMITTED,
    /**
     * The SMS dispatch to a pharmacy was refused by the provider and no other
     * pharmacy holds the order: it reached nobody. On the pharmacy work queue,
     * dispensable in-house, routable, and dispatchable again (the retry).
     */
    TRANSMISSION_FAILED,
    CANCELLED,
    DISCONTINUED,
    /** Prescription flagged by pharmacy and awaiting physician clarification. */
    PENDING_CLARIFICATION,
    /** Medication fully dispensed at an internal pharmacy. */
    DISPENSED,
    /** Only part of the prescribed quantity was dispensed. */
    PARTIALLY_FILLED,
    /** Medication not in stock; awaiting restock before dispensing. */
    PENDING_STOCK,
    /** Prescription forwarded to a partner pharmacy. */
    SENT_TO_PARTNER,
    /** Partner pharmacy acknowledged the prescription. */
    PARTNER_ACCEPTED,
    /** Partner pharmacy rejected the prescription. */
    PARTNER_REJECTED,
    /** Partner pharmacy has dispensed the medication. */
    PARTNER_DISPENSED,
    /** Prescription printed for the patient to take to an external pharmacy. */
    PRINTED_FOR_PATIENT;

    /**
     * The prescriber took the order back. Nothing downstream — a partner's
     * late SMS reply, the partner timeout sweep — may move a withdrawn order
     * to another status or ask the prescriber to act on it.
     */
    public boolean isWithdrawn() {
        return this == CANCELLED || this == DISCONTINUED;
    }

    /**
     * QA D1515: whether an order with this status is in force, the one definition every clinical
     * decision check reads when it asks what else the patient is on (the paracetamol total, the dose
     * limits, the BPA rules). A draft or an order waiting for its signature is a proposal, not an order:
     * it is judged when it is itself proposed or signed, never as something the patient already takes.
     * QA D1523: the duplicate and interaction warnings also name a proposal ({@link #isProposal()}),
     * as another order the patient may soon be on, and count nothing from it. A withdrawn order (CANCELLED, DISCONTINUED) and one a partner pharmacy
     * rejected are over. Every other status is a signed order: the supply statuses say where the
     * medicine is, not that the prescriber stopped it. An order past its stated duration or ended with
     * its stay is not settled here: its status does not say so, and the readers that count it judge
     * its dates ({@code ParacetamolFactsReader}, the duplicate check's look-back).
     */
    public boolean isInForce() {
        return switch (this) {
            case DRAFT, PENDING_SIGNATURE, CANCELLED, DISCONTINUED, PARTNER_REJECTED -> false;
            default -> true;
        };
    }

    /**
     * QA D1523: a draft or an order waiting for its signature: a proposal, never an order in force,
     * yet another order the patient may soon be on. The duplicate and interaction warnings read it
     * beside the orders in force, naming its status; no exposure total or dose limit counts it.
     */
    public boolean isProposal() {
        return this == DRAFT || this == PENDING_SIGNATURE;
    }

    /**
     * Whether this prescription is still a live authorization a refill can be
     * released against.
     *
     * <p>Note what is deliberately refillable: DISPENSED and PARTIALLY_FILLED.
     * A patient asks for a refill precisely because they have already collected
     * the medication, so treating a dispensed prescription as spent would refuse
     * every refill that matters. Only a prescription that was never signed, or
     * has been withdrawn, is genuinely un-refillable.
     *
     * <p>Single source of truth for the patient request gate, the approval gate
     * and the portal UI — they disagreed before, and the UI's version hid the
     * refill button on exactly the prescriptions patients needed it for.
     */
    public boolean isRefillable() {
        return switch (this) {
            case DRAFT, PENDING_SIGNATURE, CANCELLED, DISCONTINUED -> false;
            default -> true;
        };
    }

    /**
     * QA D780: the status as the caller reads it ("délivrée"), never the raw
     * code, for messages that name the order's current state. Uses the
     * {@code cds.enum.*} labels, which cover every value.
     */
    public String localizedLabel() {
        String key = "cds.enum." + name();
        String label = com.example.hms.utility.MessageUtil.resolve(key);
        return label == null || label.startsWith("[Missing translation]") ? name() : label;
    }
}
