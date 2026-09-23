package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.SmsService;
import com.example.hms.service.pharmacy.FillAccounting;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * T-54 — Partner pharmacy channel backed by the existing {@link SmsService}.
 * <p>
 * Operates best-effort: when the partner or patient has no phone number, or
 * the {@code SmsService} bean is absent (e.g. local dev), the method is a no-op
 * so that business flow is never blocked by a missing notification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmsPartnerNotificationChannel implements PartnerNotificationChannel {

    private final ObjectProvider<SmsService> smsServiceProvider;
    private final PartnerSmsTemplates templates;
    private final PatientLocaleResolver patientLocaleResolver;

    /** Short, human-friendly token shared with partners; prefix of the routing decision UUID. */
    @Override
    public String buildRefToken(PrescriptionRoutingDecision decision) {
        if (decision == null || decision.getId() == null) {
            return "";
        }
        return decision.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    @Override
    public String prescriptionOfferBody(PrescriptionRoutingDecision decision,
                                        Prescription prescription,
                                        String medicationSummary) {
        String initials = patientInitials(prescription != null ? prescription.getPatient() : null);
        return templates.prescriptionOffer(
                buildRefToken(decision), safeMedication(medicationSummary), initials);
    }

    @Override
    public void sendPrescriptionOffer(PrescriptionRoutingDecision decision,
                                      Prescription prescription,
                                      Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank() || prescription == null) {
            return;
        }
        String initials = patientInitials(prescription.getPatient());
        String ref = buildRefToken(decision);
        String medication = safeMedication(prescription.getMedicationName());
        // A partially filled order offers its remainder, and the partner is
        // told the number: the offer used to name the full prescribed amount
        // whatever had already been handed over (gap G3, round 3).
        String remainder = remainderLabel(decision, prescription);
        if (remainder != null) {
            trySend(phone, templates.prescriptionOfferPartial(ref, medication, remainder, initials));
            return;
        }
        trySend(phone, templates.prescriptionOffer(ref, medication, initials));
    }

    @Override
    public void sendReminder(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        trySend(phone, templates.reminder(buildRefToken(decision)));
    }

    @Override
    public void sendAutoRejected(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        trySend(phone, templates.autoRejected(buildRefToken(decision)));
    }

    @Override
    public void sendSuperseded(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        trySend(phone, templates.superseded(buildRefToken(decision)));
    }

    @Override
    public void notifyPatientAccepted(Patient patient, Pharmacy partner) {
        String phone = patientPhone(patient);
        if (phone == null || partner == null) {
            return;
        }
        Locale locale = patientLocale(patient);
        trySend(phone, templates.patientAccepted(safeName(partner.getName(), locale), locale));
    }

    @Override
    public void notifyPatientDispensed(Patient patient, Pharmacy partner) {
        String phone = patientPhone(patient);
        if (phone == null || partner == null) {
            return;
        }
        Locale locale = patientLocale(patient);
        trySend(phone, templates.patientDispensed(safeName(partner.getName(), locale), locale));
    }

    // ---------- helpers ----------

    private void trySend(String phone, String message) {
        SmsService sms = smsServiceProvider.getIfAvailable();
        if (sms == null) {
            log.debug("SmsService unavailable; skipping partner SMS");
            return;
        }
        try {
            sms.send(phone, message);
        } catch (Exception ex) {
            // Never fail the business flow because the SMS gateway is transiently unavailable.
            // Mask the destination number to avoid leaking patient/partner phone numbers into logs.
            log.warn("Partner SMS failed for {}: {}", maskPhone(phone), ex.getMessage());
        }
    }

    /**
     * Mask all but the last 4 digits of a phone number for safe logging.
     * Returns {@code ""} for null/blank input.
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String trimmed = phone.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        String tail = trimmed.substring(trimmed.length() - 4);
        return "****" + tail;
    }

    private static String patientPhone(Patient p) {
        if (p == null) {
            return null;
        }
        String primary = p.getPhoneNumberPrimary();
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        String secondary = p.getPhoneNumberSecondary();
        return (secondary != null && !secondary.isBlank()) ? secondary : null;
    }

    private static String patientInitials(Patient p) {
        if (p == null) {
            return "—";
        }
        char first = initial(p.getFirstName());
        char last = initial(p.getLastName());
        if (first == 0 && last == 0) {
            return "—";
        }
        StringBuilder sb = new StringBuilder(2);
        if (first != 0) sb.append(first);
        if (last != 0) sb.append(last);
        return sb.toString();
    }

    private static char initial(String s) {
        return (s == null || s.isBlank()) ? 0 : Character.toUpperCase(s.trim().charAt(0));
    }

    /**
     * The remainder to name in the offer, or null when there is nothing to
     * qualify: an unknown quantity, or a decision that routes exactly what
     * was prescribed.
     */
    private static String remainderLabel(PrescriptionRoutingDecision decision, Prescription prescription) {
        java.math.BigDecimal remaining = decision != null ? decision.getRemainingQuantity() : null;
        if (remaining == null) {
            return null;
        }
        // Against the LIFETIME entitlement, not the per-fill quantity: an
        // untouched prescription with one released refill owes 2 × quantity,
        // and comparing that with quantity alone announced a remainder to a
        // partner that is being offered the whole thing.
        if (remaining.compareTo(FillAccounting.expectedLifetimeQuantity(prescription)) == 0) {
            return null;
        }
        return FillAccounting.remainingLabel(remaining, prescription.getQuantityUnit());
    }

    private Locale patientLocale(Patient patient) {
        return patientLocaleResolver.resolve(patient, NotificationLocales.PATIENT_FALLBACK);
    }

    private String safeMedication(String name) {
        return (name == null || name.isBlank()) ? templates.medicationFallback() : name;
    }

    private String safeName(String name, Locale patientLocale) {
        return (name == null || name.isBlank()) ? templates.pharmacyFallback(patientLocale) : name;
    }
}
