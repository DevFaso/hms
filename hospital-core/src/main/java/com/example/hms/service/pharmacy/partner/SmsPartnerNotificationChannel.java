package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.notification.PatientMessageGate;
import com.example.hms.enums.PatientNotificationEvent;
import com.example.hms.service.pharmacy.FillAccounting;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.i18n.PatientLocaleResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * T-54 — Partner pharmacy channel backed by the existing {@link com.example.hms.service.SmsService}.
 * <p>
 * Patient notices pass through the consent and current-recipient verification gate. Only
 * partner notices use the partner-only raw transport.
 *
 * Operates best-effort: when the partner or patient has no phone number, or
 * the {@code SmsService} bean is absent (e.g. local dev), the method is a no-op
 * so that business flow is never blocked by a missing notification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmsPartnerNotificationChannel implements PartnerNotificationChannel {

    private final PartnerPharmacySmsTransport partnerSms;
    private final PatientMessageGate patientMessages;
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
                buildRefToken(decision), withAllergyClause(safeMedication(medicationSummary), prescription), initials);
    }

    /**
     * QA D3865: the patient's known allergies, and a line prescribed despite one (with the prescriber's reason), on
     * every offer a partner pharmacy dispenses from. Set by Spring; a hand-built channel in a unit test may leave it
     * out.
     */
    private com.example.hms.service.pharmacy.PrescriptionAllergyNotice allergyNotice;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAllergyNotice(com.example.hms.service.pharmacy.PrescriptionAllergyNotice allergyNotice) {
        this.allergyNotice = allergyNotice;
    }

    /** The medicine, then the allergy clause in the partner's language when there is one. */
    String withAllergyClause(String medication, Prescription prescription) {
        if (allergyNotice == null || prescription == null) {
            return medication;
        }
        String clause = allergyNotice.smsClause(prescription,
                com.example.hms.service.i18n.NotificationLocales.PARTNER_PHARMACY);
        return clause == null || clause.isBlank() ? medication : medication + ". " + clause;
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
        String medication = withAllergyClause(safeMedication(prescription.getMedicationName()), prescription);
        // A partially filled order offers its remainder, and the partner is
        // told the number: the offer used to name the full prescribed amount
        // whatever had already been handed over (gap G3, round 3).
        String remainder = remainderLabel(decision, prescription);
        if (remainder != null) {
            partnerSms.send(partner, templates.prescriptionOfferPartial(ref, medication, remainder, initials));
            return;
        }
        partnerSms.send(partner, templates.prescriptionOffer(ref, medication, initials));
    }

    @Override
    public void sendReminder(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        partnerSms.send(partner, templates.reminder(buildRefToken(decision)));
    }

    @Override
    public void sendAutoRejected(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        partnerSms.send(partner, templates.autoRejected(buildRefToken(decision)));
    }

    @Override
    public void sendSuperseded(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        partnerSms.send(partner, templates.superseded(buildRefToken(decision)));
    }

    @Override
    public void sendWithdrawn(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        partnerSms.send(partner, templates.withdrawn(buildRefToken(decision)));
    }

    @Override
    public void sendHeldForReview(PrescriptionRoutingDecision decision, Pharmacy partner) {
        String phone = partner != null ? partner.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        partnerSms.send(partner, templates.heldForReview(buildRefToken(decision)));
    }

    @Override
    public void notifyPatientAccepted(PrescriptionRoutingDecision decision, Patient patient, Pharmacy partner) {
        if (patient == null || partner == null) return;
        var notice = patientNotice(PatientNotificationEvent.PHARMACY_OUT_OF_STOCK, decision, patient);
        if (notice == null) return;
        Locale locale = patientLocale(patient);
        patientMessages.smsAfterCommit(patient,
            notice,
            templates.patientAccepted(safeName(partner.getName(), locale), locale));
    }

    @Override
    public void notifyPatientDispensed(PrescriptionRoutingDecision decision, Patient patient, Pharmacy partner) {
        if (patient == null || partner == null) return;
        var notice = patientNotice(PatientNotificationEvent.PHARMACY_DISPENSED, decision, patient);
        if (notice == null) return;
        Locale locale = patientLocale(patient);
        patientMessages.smsAfterCommit(patient,
            notice,
            templates.patientDispensed(safeName(partner.getName(), locale), locale));
    }

    /** Trace the originating hospital and this offer, never the partner's hospital or a patient-wide key. */
    private static PatientMessageGate.Notice patientNotice(PatientNotificationEvent event,
            PrescriptionRoutingDecision decision, Patient patient) {
        Prescription prescription = decision != null ? decision.getPrescription() : null;
        if (decision == null || decision.getId() == null || prescription == null
                || prescription.getHospital() == null || prescription.getHospital().getId() == null
                || prescription.getPatient() == null || patient.getId() == null
                || !patient.getId().equals(prescription.getPatient().getId())) return null;
        return new PatientMessageGate.Notice(event, prescription.getHospital().getId(), decision.getId());
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

    /** K4: a partner reads the patient's initials, never the name ({@link com.example.hms.utility.SmsPatientLabel}). */
    private static String patientInitials(Patient p) {
        return com.example.hms.utility.SmsPatientLabel.initials(p);
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
        // The partner reads the offer in its SMS language (D1002 class: its decimal separator too).
        return FillAccounting.remainingLabel(remaining, prescription.getQuantityUnit(),
                com.example.hms.service.i18n.NotificationLocales.PARTNER_PHARMACY);
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
