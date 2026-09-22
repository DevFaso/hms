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

    /**
     * The offer text for one decision — reference token, medication summary,
     * patient initials and the reply codes — so every path that hands a
     * prescription to an outside pharmacy (stock-out routing, SMS dispatch)
     * sends a message the inbound reply parser recognises.
     */
    String prescriptionOfferBody(PrescriptionRoutingDecision decision, Prescription prescription,
                                 String medicationSummary);

    /** Outbound: offer a new prescription to a partner pharmacy. */
    void sendPrescriptionOffer(PrescriptionRoutingDecision decision, Prescription prescription, Pharmacy partner);

    /** Outbound: remind a partner that has not yet responded. */
    void sendReminder(PrescriptionRoutingDecision decision, Pharmacy partner);

    /** Outbound: notify partner that the prescription has been auto-rejected after timeout. */
    void sendAutoRejected(PrescriptionRoutingDecision decision, Pharmacy partner);

    /**
     * Tell a pharmacy that the offer it is holding has been handed to another
     * one, so it stops preparing and knows its reply will not be applied.
     * Distinct from {@link #sendAutoRejected} on purpose: nothing timed out.
     */
    void sendSuperseded(PrescriptionRoutingDecision decision, Pharmacy partner);

    /** Outbound to patient: partner accepted. */
    void notifyPatientAccepted(Patient patient, Pharmacy partner);

    /** Outbound to patient: partner dispensed. */
    void notifyPatientDispensed(Patient patient, Pharmacy partner);
}
