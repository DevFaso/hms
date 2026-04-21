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
 * The Rx reference token is appended so the partner's reply can be parsed
 * unambiguously when multiple prescriptions are active.
 */
public final class PartnerSmsTemplates {

    private PartnerSmsTemplates() {
    }

    /** Outbound: new prescription offered to a partner pharmacy. */
    public static String prescriptionOffer(String refToken, String medicationName, String patientInitials) {
        return "HMS Rx " + refToken + " : " + medicationName
                + " pour " + patientInitials
                + ". Répondez 1 pour accepter, 2 pour refuser.";
    }

    /** Outbound: reminder if no reply received in 2 hours. */
    public static String reminder(String refToken) {
        return "HMS Rx " + refToken + " : rappel, aucune réponse reçue."
                + " Répondez 1 pour accepter, 2 pour refuser.";
    }

    /** Outbound: auto-rejection notice after timeout expiry. */
    public static String autoRejected(String refToken) {
        return "HMS Rx " + refToken + " : délai dépassé, ordonnance refermée."
                + " Merci.";
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
