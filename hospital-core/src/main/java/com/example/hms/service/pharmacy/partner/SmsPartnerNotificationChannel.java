package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.SmsService;
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

    /** Short, human-friendly token shared with partners; prefix of the routing decision UUID. */
    @Override
    public String buildRefToken(PrescriptionRoutingDecision decision) {
        if (decision == null || decision.getId() == null) {
            return "";
        }
        return decision.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
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
        trySend(phone, PartnerSmsTemplates.prescriptionOffer(
                ref, safeMedication(prescription.getMedicationName()), initials));
    }

    @Override
    public void sendReminder(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        trySend(phone, PartnerSmsTemplates.reminder(buildRefToken(decision)));
    }

    @Override
    public void sendAutoRejected(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        trySend(phone, PartnerSmsTemplates.autoRejected(buildRefToken(decision)));
    }

    @Override
    public void notifyPatientAccepted(Patient patient, Pharmacy partner) {
        String phone = patientPhone(patient);
        if (phone == null || partner == null) {
            return;
        }
        trySend(phone, PartnerSmsTemplates.patientAccepted(safeName(partner.getName())));
    }

    @Override
    public void notifyPatientDispensed(Patient patient, Pharmacy partner) {
        String phone = patientPhone(patient);
        if (phone == null || partner == null) {
            return;
        }
        trySend(phone, PartnerSmsTemplates.patientDispensed(safeName(partner.getName())));
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

    private static String safeMedication(String name) {
        return (name == null || name.isBlank()) ? "médicament" : name;
    }

    private static String safeName(String name) {
        return (name == null || name.isBlank()) ? "la pharmacie partenaire" : name;
    }
}
