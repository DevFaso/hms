package com.example.hms.service.pharmacy.partner;

import com.example.hms.service.i18n.NotificationLocales;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * T-60 — SMS templates for partner pharmacy exchange, read from the message
 * bundle (gap G14) instead of French literals.
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
 * <p>
 * Partner-facing bodies render in {@link NotificationLocales#PARTNER_PHARMACY};
 * patient-facing bodies take the patient's resolved locale from the caller.
 */
@Component
@RequiredArgsConstructor
public class PartnerSmsTemplates {

    private final MessageSource messageSource;

    /** Outbound: new prescription offered to a partner pharmacy, in full. */
    public String prescriptionOffer(String refToken, String medicationName, String patientInitials) {
        return partner("sms.partner.offer", refToken, medicationName, patientInitials);
    }

    /**
     * Outbound: the REMAINDER of a partially filled prescription offered to a
     * partner. A separate template rather than an optional clause, because an
     * empty clause leaves a partner reading a stray bracket — and this is the
     * number that stops a second full course being handed over.
     */
    public String prescriptionOfferPartial(String refToken, String medicationName,
                                           String remainingLabel, String patientInitials) {
        return partner("sms.partner.offer.partial", refToken, medicationName, remainingLabel, patientInitials);
    }

    /** Outbound: reminder if no reply received in 2 hours. */
    public String reminder(String refToken) {
        return partner("sms.partner.reminder", refToken);
    }

    /** Outbound: auto-rejection notice after timeout expiry. */
    public String autoRejected(String refToken) {
        return partner("sms.partner.autoRejected", refToken);
    }

    /** Outbound to patient: partner accepted the prescription. */
    public String patientAccepted(String pharmacyName, Locale patientLocale) {
        return messageSource.getMessage("sms.partner.patientAccepted",
                new Object[]{pharmacyName}, patientLocale);
    }

    /** Outbound to patient: partner dispensed the medication. */
    public String patientDispensed(String pharmacyName, Locale patientLocale) {
        return messageSource.getMessage("sms.partner.patientDispensed",
                new Object[]{pharmacyName}, patientLocale);
    }

    /** Placeholder for a prescription with no medication name, in the partner's language. */
    public String medicationFallback() {
        return partner("sms.partner.medicationFallback");
    }

    /** Placeholder for a partner pharmacy with no name, in the patient's language. */
    public String pharmacyFallback(Locale patientLocale) {
        return messageSource.getMessage("sms.partner.pharmacyFallback", null, patientLocale);
    }

    private String partner(String key, Object... args) {
        return messageSource.getMessage(key, args, NotificationLocales.PARTNER_PHARMACY);
    }
}
