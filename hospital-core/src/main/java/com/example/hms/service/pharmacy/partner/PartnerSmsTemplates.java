package com.example.hms.service.pharmacy.partner;

/**
 * T-60 — French SMS templates for partner pharmacy exchange.
 * <p>
 * All messages are intentionally short to fit a single SMS (160 GSM-7 chars)
 * whenever possible. Reply codes keep parsing simple on partners' basic phones:
 * <ul>
 *   <li>{@code 1} = accept</li>
 *   <li>{@code 2} = reject</li>
 *   <li>{@code 3} = confirm dispensed</li>
 *   <li>{@code 0} = cancel / unsubscribe (not auto-handled)</li>
 * </ul>
 * Every outbound message carries the Rx reference token AND shows it inside
 * the reply it asks for ({@code « 1 ABC12 »}): the inbound parser requires a
 * token, so an offer that only said "Répondez 1 pour accepter" produced bare
 * replies that were parsed and then discarded — the decision stayed PENDING
 * and auto-rejected four hours later.
 */
public final class PartnerSmsTemplates {

    private static final String RX_PREFIX = "HMS Rx ";

    private PartnerSmsTemplates() {
    }

    /** Outbound: new prescription offered to a partner pharmacy. */
    public static String prescriptionOffer(String refToken, String medicationName, String patientInitials) {
        return RX_PREFIX + refToken + " : " + medicationName
                + " pour " + patientInitials
                + ". " + replyInstructions(refToken);
    }

    /** Outbound: reminder if no reply received in 2 hours. */
    public static String reminder(String refToken) {
        return RX_PREFIX + refToken + " : rappel, aucune réponse reçue."
                + " " + replyInstructions(refToken);
    }

    /**
     * The reply the parser can actually act on: the code AND the reference,
     * spelled out. Quoting the reference is not decoration — a reply without
     * it cannot be matched to a prescription and is dropped.
     */
    static String replyInstructions(String refToken) {
        return "Répondez « 1 " + refToken + " » pour accepter, « 2 " + refToken + " » pour refuser.";
    }

    /** Outbound: auto-rejection notice after timeout expiry. */
    public static String autoRejected(String refToken) {
        return RX_PREFIX + refToken + " : délai dépassé, ordonnance refermée."
                + " Merci.";
    }

    /**
     * Outbound: the offer has been handed to another pharmacy. Deliberately not
     * {@link #autoRejected}: nothing timed out, and telling a pharmacy its
     * deadline passed when the prescriber simply chose elsewhere is a lie the
     * pharmacy would act on.
     */
    public static String superseded(String refToken) {
        return RX_PREFIX + refToken + " : ordonnance confiée à une autre pharmacie."
                + " Inutile de préparer. Merci.";
    }

    /** Outbound to patient: partner accepted the prescription. */
    public static String patientAccepted(String pharmacyName) {
        return "Bonjour, votre ordonnance a été acceptée par " + pharmacyName
                + ". Vous pouvez vous y rendre.";
    }

    /** Outbound to patient: partner dispensed the medication. */
    public static String patientDispensed(String pharmacyName) {
        return "Bonjour, votre médicament a été délivré par " + pharmacyName
                + ". Bonne santé.";
    }
}
