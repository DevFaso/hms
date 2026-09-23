package com.example.hms.service.impl;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.enums.PharmacyType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.model.prescription.PrescriptionTransmission;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchRequestDTO;
import com.example.hms.payload.dto.prescription.PrescriptionSmsDispatchResponseDTO;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.PrescriptionRoutingDecisionRepository;
import com.example.hms.repository.prescription.PrescriptionTransmissionRepository;
import com.example.hms.service.PrescriptionSmsDispatchService;
import com.example.hms.service.SmsService;
import com.example.hms.service.pharmacy.partner.PartnerNotificationChannel;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Community / partner pharmacy dispatch by SMS.
 *
 * <p>G1: a dispatch is a routing decision, not a fire-and-forget text. It
 * records a PENDING {@link PrescriptionRoutingDecision} of type PARTNER against
 * the chosen pharmacy, moves the prescription to SENT_TO_PARTNER (out of the
 * in-house dispense queue, so it cannot be filled twice), and sends the same
 * offer template the stock-out routing uses — reference token included — so
 * the pharmacy's reply ("1 / 2 / 3 &lt;ref&gt;") lands in the partner webhook and
 * the reminder / auto-reject sweep applies unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrescriptionSmsDispatchServiceImpl implements PrescriptionSmsDispatchService {

    private static final String CHANNEL_SMS = "SMS";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String STATUS_SENT = "SENT";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_BODY_CHARS = 480;

    /**
     * States a prescription may be handed to an outside pharmacy from: signed
     * and unclaimed (SIGNED / TRANSMITTED), refused by the previous pharmacy or
     * waiting on stock (PARTNER_REJECTED / PENDING_STOCK), or already offered to
     * one (SENT_TO_PARTNER).
     *
     * <p>SENT_TO_PARTNER is here because a silent pharmacy is the ordinary case
     * a clinician has to escape: without it the only way out was the four-hour
     * auto-reject sweep, and {@link #supersedeOpenDecisions} could never run at
     * all. Re-dispatching supersedes the offer the previous pharmacy holds.
     */
    static final Set<PrescriptionStatus> DISPATCHABLE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTNER_REJECTED,
            PrescriptionStatus.PENDING_STOCK,
            PrescriptionStatus.SENT_TO_PARTNER
    );

    /** A decision the pharmacy could still answer (mirrors PartnerExchangeService.OPEN_STATUSES). */
    private static final Set<RoutingDecisionStatus> OPEN_DECISION_STATUSES = Set.of(
            RoutingDecisionStatus.PENDING,
            RoutingDecisionStatus.ACCEPTED
    );

    private final PrescriptionRepository prescriptionRepository;
    private final PharmacyRepository pharmacyRepository;
    private final PrescriptionTransmissionRepository transmissionRepository;
    private final PrescriptionRoutingDecisionRepository routingDecisionRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;
    private final PartnerNotificationChannel partnerChannel;
    private final ControllerAuthUtils authUtils;

    @Override
    @Transactional
    public PrescriptionSmsDispatchResponseDTO dispatch(Authentication auth,
                                                        UUID prescriptionId,
                                                        PrescriptionSmsDispatchRequestDTO request) {
        authUtils.requireAuth(auth);

        // Locked for the duration: two dispatches racing here would each see
        // no open decision to supersede and leave two live offers, either of
        // which a pharmacy could accept — the double fill SENT_TO_PARTNER
        // exists to prevent.
        Prescription rx = prescriptionRepository.findByIdForUpdate(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
        requireCallerHospital(auth, rx);
        Pharmacy pharmacy = pharmacyRepository.findById(request.getPharmacyId())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));

        refuseWhileAwaitingClarification(rx);
        validateScope(rx, pharmacy);
        requireDispatchable(rx);
        String phone = requirePharmacyPhone(pharmacy);
        User decidedBy = resolveCurrentUser(auth);
        supersedeOpenDecisions(rx, pharmacy);

        // The decision is persisted first: its id is the reference token the
        // pharmacy quotes back, so the body cannot be built before it exists.
        PrescriptionRoutingDecision decision = routingDecisionRepository.save(
                PrescriptionRoutingDecision.builder()
                        .prescription(rx)
                        .routingType(RoutingType.PARTNER)
                        .targetPharmacy(pharmacy)
                        .decidedByUser(decidedBy)
                        .decidedForPatient(rx.getPatient())
                        .reason(dispatchReason(pharmacy, request.getNote()))
                        .status(RoutingDecisionStatus.PENDING)
                        .decidedAt(LocalDateTime.now())
                        .build());

        String body = buildSmsBody(decision, rx, request.getNote());
        PrescriptionTransmission transmission = sendAndPersist(rx, pharmacy, phone, body);

        applyDispatchToPrescription(rx, pharmacy, phone, transmission);
        log.info("Dispatched prescription {} via SMS to pharmacy {} ({}); routing decision {}",
                prescriptionId, pharmacy.getId(), phone, decision.getId());

        return PrescriptionSmsDispatchResponseDTO.builder()
                .prescriptionId(rx.getId())
                .transmissionId(transmission.getId())
                .pharmacyId(pharmacy.getId())
                .pharmacyName(pharmacy.getName())
                .destinationPhone(phone)
                .status(transmission.getStatus())
                .dispatchedAt(transmission.getLastAttemptedAt())
                .build();
    }

    /**
     * Tenant isolation, the same 404-not-403 idiom as every other prescription
     * read: a caller whose active hospital is not the prescription's must not
     * be able to tell it from one that does not exist.
     *
     * <p>Only a super-admin in global view is unscoped. A null active hospital
     * otherwise means a caller with no active assignment, not a caller entitled
     * to every tenant — treating the two alike let exactly those callers
     * dispatch another hospital's prescription.
     */
    private void requireCallerHospital(Authentication auth, Prescription rx) {
        UUID callerHospitalId = authUtils.currentHospitalId(auth);
        if (callerHospitalId == null) {
            if (authUtils.hasAuthority(auth, ROLE_SUPER_ADMIN)) {
                return;
            }
            throw new ResourceNotFoundException("Prescription not found");
        }
        UUID rxHospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        if (!callerHospitalId.equals(rxHospitalId)) {
            throw new ResourceNotFoundException("Prescription not found");
        }
    }

    /**
     * The clarification hold applies to every external-fill exit, and this is
     * the third one (the other two are the partner route and the printed
     * copy, both refused by {@code StockOutRoutingServiceImpl}). Without it a
     * pharmacist could ask the prescriber a question and then SMS the order
     * to a community pharmacy anyway.
     *
     * <p>Deliberately the only status rule added here: this file's broader
     * dispatchable-status question belongs to the community-dispatch PR.
     */
    private void refuseWhileAwaitingClarification(Prescription rx) {
        if (rx.getStatus() == PrescriptionStatus.PENDING_CLARIFICATION) {
            throw new BusinessException("This prescription is awaiting the prescriber's clarification "
                + "and cannot be dispatched until it is resolved.");
        }
    }

    private void validateScope(Prescription rx, Pharmacy pharmacy) {
        if (pharmacy.getPharmacyType() == PharmacyType.HOSPITAL_DISPENSARY) {
            throw new BusinessException(
                "Hospital-dispensary pharmacies are dispensed in-house and cannot be dispatched by SMS.");
        }
        if (!pharmacy.isActive()) {
            throw new BusinessException("Selected pharmacy is inactive and cannot accept new dispatches.");
        }
        UUID rxHospitalId = rx.getHospital() != null ? rx.getHospital().getId() : null;
        UUID pharmHospitalId = pharmacy.getHospital() != null ? pharmacy.getHospital().getId() : null;
        if (rxHospitalId == null || pharmHospitalId == null
                || !rxHospitalId.equals(pharmHospitalId)) {
            throw new AccessDeniedException(
                "Pharmacy and prescription must belong to the same hospital.");
        }
    }

    private static void requireDispatchable(Prescription rx) {
        if (!DISPATCHABLE_STATUSES.contains(rx.getStatus())) {
            throw new BusinessException(
                "Only a signed prescription, or one a pharmacy has been offered, refused or placed on "
                    + "back order, can be dispatched by SMS; this one is " + rx.getStatus() + ".");
        }
    }

    /**
     * Re-dispatch closes the offer the previous PHARMACY still held: the old
     * reference token must not act on the prescription once another pharmacy
     * holds it, and the inbound finder only matches open decisions.
     *
     * <p>Only PARTNER decisions are superseded. A BACKORDER decision is not an
     * offer to anybody — it is the record that the medication is awaited, with
     * the estimated restock date on it. Cancelling it here threw that date away
     * the moment a back-ordered prescription was sent to a pharmacy, so a
     * refusal left nothing behind at all; left alone, the back order still
     * stands when the pharmacy says no.
     *
     * <p>The superseded pharmacy is told, best-effort: it was asked to prepare
     * a prescription and would otherwise keep waiting on a reply that can no
     * longer be applied.
     */
    private void supersedeOpenDecisions(Prescription rx, Pharmacy newTarget) {
        List<PrescriptionRoutingDecision> open = routingDecisionRepository.findByPrescriptionId(rx.getId())
                .stream()
                .filter(d -> d.getRoutingType() == RoutingType.PARTNER)
                .filter(d -> OPEN_DECISION_STATUSES.contains(d.getStatus()))
                .toList();
        for (PrescriptionRoutingDecision d : open) {
            if (isSameTarget(d, newTarget)) {
                // Re-sending to the same pharmacy replaces its offer; it has
                // not lost anything and must not be told to stop preparing
                // moments after being asked again.
                log.info("Routing decision {} re-sent to the same pharmacy for prescription {}",
                        d.getId(), rx.getId());
                continue;
            }
            d.setStatus(RoutingDecisionStatus.CANCELLED);
            d.setReason(appendReason(d.getReason(),
                    "Superseded: re-dispatched by SMS to " + newTarget.getName()));
            routingDecisionRepository.save(d);
            log.info("Routing decision {} superseded by re-dispatch of prescription {}", d.getId(), rx.getId());
            notifySuperseded(d);
        }
    }

    private static boolean isSameTarget(PrescriptionRoutingDecision decision, Pharmacy newTarget) {
        Pharmacy current = decision.getTargetPharmacy();
        return current != null && current.getId() != null && current.getId().equals(newTarget.getId());
    }

    /**
     * Tell the superseded pharmacy — but only once the dispatch that replaced
     * its offer has actually committed. Sent inline, this went out before the
     * new pharmacy's SMS: if that send failed the transaction rolled back and
     * the old pharmacy had been told to drop an offer that was still live.
     *
     * <p>The LAZY pharmacy is dereferenced HERE, inside the transaction. The
     * callback runs with no persistence context, where touching a proxy would
     * throw instead of sending anything.
     */
    private void notifySuperseded(PrescriptionRoutingDecision superseded) {
        Pharmacy previous = superseded.getTargetPharmacy();
        String phone = previous != null ? previous.getPhoneNumber() : null;
        if (phone == null || phone.isBlank()) {
            return;
        }
        UUID decisionId = superseded.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                partnerChannel.sendSuperseded(superseded, previous);
            } catch (Exception ex) {
                log.warn("Could not tell the superseded pharmacy about decision {}: {}",
                        decisionId, ex.getMessage());
            }
        });
    }

    private static String appendReason(String existing, String suffix) {
        String joined = (existing == null || existing.isBlank()) ? suffix : existing + ". " + suffix;
        return joined.length() > 1024 ? joined.substring(0, 1024) : joined;
    }

    private String requirePharmacyPhone(Pharmacy pharmacy) {
        String phone = pharmacy.getPhoneNumber();
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("Selected pharmacy has no phone number on file.");
        }
        return phone;
    }

    private User resolveCurrentUser(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException("Unable to determine current user"));
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private static String dispatchReason(Pharmacy pharmacy, String note) {
        String reason = "Dispatched by SMS to " + pharmacy.getPharmacyType() + " " + pharmacy.getName();
        if (note != null && !note.isBlank()) {
            reason += ". Note: " + note.trim();
        }
        return reason.length() > 1024 ? reason.substring(0, 1024) : reason;
    }

    private PrescriptionTransmission sendAndPersist(Prescription rx, Pharmacy pharmacy,
                                                    String phone, String body) {
        PrescriptionTransmission transmission = PrescriptionTransmission.builder()
                .prescription(rx)
                .channel(CHANNEL_SMS)
                .destination(phone)
                .destinationReference(pharmacy.getId().toString())
                .status(STATUS_SENT)
                .attemptCount(1)
                .lastAttemptedAt(LocalDateTime.now())
                .build();

        try {
            smsService.send(phone, body);
        } catch (Exception ex) {
            transmission.setStatus(STATUS_FAILED);
            transmission.setStatusReason(ex.getMessage());
            transmissionRepository.save(transmission);
            log.warn("Prescription SMS dispatch failed for {} → {}: {}",
                    rx.getId(), phone, ex.getMessage(), ex);
            throw new BusinessException("SMS provider rejected the message: " + ex.getMessage());
        }
        return transmissionRepository.save(transmission);
    }

    private void applyDispatchToPrescription(Prescription rx, Pharmacy pharmacy, String phone,
                                              PrescriptionTransmission transmission) {
        rx.setStatus(PrescriptionStatus.SENT_TO_PARTNER);
        rx.setDispatchChannel(CHANNEL_SMS);
        rx.setDispatchStatus(STATUS_SENT);
        rx.setDispatchedAt(transmission.getLastAttemptedAt());
        rx.setDispatchReference(pharmacy.getId().toString());
        rx.setPharmacyId(pharmacy.getId());
        rx.setPharmacyName(pharmacy.getName());
        rx.setPharmacyContact(phone);
        rx.setPharmacyAddress(pharmacy.getAddressLine1());
        prescriptionRepository.save(rx);
    }

    /**
     * The partner offer template (token, patient initials, reply codes) around
     * the prescribing detail a community pharmacy needs to fill. The detail is
     * what gets cut when the SMS budget runs out, never the token or the reply
     * instructions.
     */
    private String buildSmsBody(PrescriptionRoutingDecision decision, Prescription rx, String note) {
        String details = medicationSummary(rx, note);
        int frame = partnerChannel.prescriptionOfferBody(decision, rx, "").length();
        int budget = Math.max(0, MAX_BODY_CHARS - frame);
        if (details.length() > budget) {
            details = details.substring(0, budget);
        }
        return partnerChannel.prescriptionOfferBody(decision, rx, details);
    }

    private static String medicationSummary(Prescription rx, String note) {
        StringBuilder sb = new StringBuilder(safe(rx.getMedicationName()));
        appendDose(sb, rx);
        appendIfPresent(sb, rx.getRoute(), " ");
        appendIfPresent(sb, rx.getFrequency(), " ");
        appendIfPresent(sb, rx.getDuration(), " x ");
        appendIfNotBlank(sb, rx.getInstructions(), ". ");
        appendIfNotBlank(sb, note, ". Note: ");
        return sb.toString();
    }

    private static void appendDose(StringBuilder sb, Prescription rx) {
        if (rx.getDosage() == null) {
            return;
        }
        sb.append(' ').append(rx.getDosage());
        if (rx.getDoseUnit() != null) {
            sb.append(rx.getDoseUnit());
        }
    }

    private static void appendIfPresent(StringBuilder sb, String value, String prefix) {
        if (value != null) {
            sb.append(prefix).append(value);
        }
    }

    private static void appendIfNotBlank(StringBuilder sb, String value, String prefix) {
        if (value != null && !value.isBlank()) {
            sb.append(prefix).append(value.trim());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
