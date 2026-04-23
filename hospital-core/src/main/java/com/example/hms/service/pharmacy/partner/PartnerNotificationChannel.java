package com.example.hms.service.pharmacy.partner;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;

/**
 * T-53 — Abstraction over the channels HMS uses to exchange prescription messages
 * with a partner pharmacy (SMS now, WhatsApp / REST / portal later).
 * <p>
 * Implementations are free to no-op when the target pharmacy lacks contact info
 * for their channel; the caller stays channel-agnostic.
 */
public interface PartnerNotificationChannel {

    /** Returns a short reference token that the partner must include in replies. */
    String buildRefToken(PrescriptionRoutingDecision decision);

    /** Outbound: offer a new prescription to a partner pharmacy. */
    void sendPrescriptionOffer(PrescriptionRoutingDecision decision, Prescription prescription, Pharmacy partner);

    /** Outbound: remind a partner that has not yet responded. */
    void sendReminder(PrescriptionRoutingDecision decision, Pharmacy partner);

    /** Outbound: notify partner that the prescription has been auto-rejected after timeout. */
    void sendAutoRejected(PrescriptionRoutingDecision decision, Pharmacy partner);

    /** Outbound to patient: partner accepted. */
    void notifyPatientAccepted(Patient patient, Pharmacy partner);

    /** Outbound to patient: partner dispensed. */
    void notifyPatientDispensed(Patient patient, Pharmacy partner);
}
