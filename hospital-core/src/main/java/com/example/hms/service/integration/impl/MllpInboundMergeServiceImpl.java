package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Inbound {@code ADT^A40} patient merge (Tier 2 item 41).
 *
 * <p>See {@link MllpInboundMergeService} for why this enforces the tenant
 * boundary itself instead of trusting {@code EmpiServiceImpl}'s guards, which
 * are no-ops on a thread with no security context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundMergeServiceImpl implements MllpInboundMergeService {

    /** {@code integration_message_event.message_type} for this path. */
    private static final String MESSAGE_TYPE = "ADT^A40";

    private final EmpiService empiService;
    private final PatientHospitalRegistrationRepository registrationRepository;
    // Last so existing positional constructor calls only append.
    private final IntegrationMessageRecorder messageRecorder;

    @Override
    @Transactional
    public MllpInboundOutcome processMerge(ParsedMergeMessage parsed,
                                           Hospital receivingHospital,
                                           String sendingApplication,
                                           String sendingFacility,
                                           String messageControlId,
                                           String rawMessageBody) {
        String integrationId = buildIntegrationId(sendingApplication, sendingFacility);
        UUID organizationId = organizationIdOf(receivingHospital);
        if (parsed == null
                || !StringUtils.hasText(parsed.survivingMrn())
                || !StringUtils.hasText(parsed.priorMrn())) {
            log.warn("MLLP A40 rejected — missing PID-3 or MRG-1 (sender={}/{} hospital={})",
                sendingApplication, sendingFacility,
                receivingHospital != null ? receivingHospital.getId() : null);
            recordReject(integrationId, organizationId, rawMessageBody,
                "missing PID-3 or MRG-1");
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (receivingHospital == null || receivingHospital.getId() == null) {
            log.warn("MLLP A40 rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            recordReject(integrationId, organizationId, rawMessageBody,
                "no resolved hospital");
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        String survivingMrn = parsed.survivingMrn().trim();
        String priorMrn = parsed.priorMrn().trim();

        if (survivingMrn.equalsIgnoreCase(priorMrn)) {
            // Not an error worth alarming about, but not a merge either.
            // The identifier itself stays out of the log line: PID-3 is an
            // MRN, and an MRN in a log is PHI wherever that log ends up.
            log.warn("MLLP A40 rejected — PID-3 and MRG-1 are the same identifier "
                + "(sender={}/{} hospital={})",
                sendingApplication, sendingFacility, receivingHospital.getId());
            recordReject(integrationId, organizationId, rawMessageBody,
                "PID-3 and MRG-1 are the same identifier");
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        UUID hospitalId = receivingHospital.getId();

        Optional<UUID> survivor = resolvePatient(survivingMrn);
        Optional<UUID> retiree = resolvePatient(priorMrn);
        if (survivor.isEmpty() || retiree.isEmpty()) {
            // Deliberately NOT auto-provisioned. An unrecognised identifier in
            // a merge message means the two systems disagree about who exists,
            // and inventing the missing side would bake that disagreement in.
            // Which side was unknown stays out of the log line, and the
            // identifiers stay out of it altogether: an MRN is PHI.
            log.warn("MLLP A40 rejected — unknown identifier(s) (sender={}/{} hospital={})",
                sendingApplication, sendingFacility, hospitalId);
            recordReject(integrationId, organizationId, rawMessageBody,
                "identifier not found");
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        UUID survivingPatientId = survivor.get();
        UUID retiringPatientId = retiree.get();

        // THE GATE — and it runs BEFORE any other answer that depends on what
        // these two identifiers are to each other.
        //
        // EmpiServiceImpl's own tenant checks resolve the caller's hospital
        // from the security context, and there is none on this thread:
        // isVisibleToCaller reads a null active hospital as "unscoped, allow".
        // Without this, an allowlisted sender could merge any two patients in
        // the system. BOTH sides, not just one: merging a stranger's record
        // INTO a local patient is as damaging as the reverse, and only
        // checking the survivor would permit it.
        //
        // Order matters as much as the check. The already-merged no-op below
        // answers AA, and answering it before this gate told a sender that two
        // identifiers it does not own resolve to one patient somewhere else —
        // the same oracle wearing an accept instead of a reject.
        //
        // And a sender that owns ONE of the two gets the answer a sender that
        // owns neither gets. If "both registered" were distinguishable from
        // "one registered", an allowlisted sender could pair its own local MRN
        // with any candidate identifier and read off whether that candidate
        // exists in another hospital. Partial ownership is not partial
        // permission, so it is not a partial answer either.
        if (!isRegisteredHere(survivingPatientId, hospitalId)
                || !isRegisteredHere(retiringPatientId, hospitalId)) {
            // Same outcome as the unknown identifier above — same ACK code,
            // same ACK text. The reason survives on the integration message
            // row, which the sender cannot read. Nor, today, can the
            // hospital's own integration operator: the only read surface is
            // /super-admin/integration-messages (SUPER_ADMIN), so diagnosing
            // a misconfigured sender means escalating. Widening that surface
            // is a separate change.
            log.warn("MLLP A40 cross-tenant reject — the two patients are not both "
                + "registered at hospital={} (sender={}/{})",
                hospitalId, sendingApplication, sendingFacility);
            recordReject(integrationId, organizationId, rawMessageBody,
                "cross-tenant rejection");
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        if (survivingPatientId.equals(retiringPatientId)) {
            // Two different MRNs already resolving to one patient — the merge
            // this message asks for has effectively happened. Accepting keeps
            // a resend idempotent instead of parking a permanent AE in the
            // sender's queue for work that is already done.
            log.info("MLLP A40 no-op — both identifiers already resolve to patient {} "
                + "(sender={}/{} hospital={} msgCtrlId={})",
                survivingPatientId, sendingApplication, sendingFacility,
                hospitalId, messageControlId);
            return MllpInboundOutcome.ACCEPTED;
        }

        try {
            empiService.mergePatients(
                survivingPatientId, retiringPatientId,
                // AUTOMATED, not MANUAL: no human made this call, and the
                // merge event should not read as though one did.
                EmpiMergeType.AUTOMATED,
                buildNotes(survivingMrn, priorMrn, sendingApplication, sendingFacility,
                    messageControlId));
        } catch (RuntimeException ex) {
            // Already merged, or a domain rule the merge service owns. AE
            // rather than AA: the sender's request was not applied and their
            // queue should say so.
            log.warn("MLLP A40 refused by the merge service — sender={}/{} hospital={}: {}",
                sendingApplication, sendingFacility, hospitalId, ex.getMessage());
            recordReject(integrationId, organizationId, rawMessageBody,
                "refused by the merge service");
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        log.info("MLLP A40 applied — patients {} <- {} "
            + "sender={}/{} hospital={} msgCtrlId={}",
            survivingPatientId, retiringPatientId,
            sendingApplication, sendingFacility, hospitalId, messageControlId);
        return MllpInboundOutcome.ACCEPTED;
    }

    /**
     * Best-effort FAILED record. The recorder runs in REQUIRES_NEW and
     * swallows its own exceptions, so the row survives this transaction
     * rolling back; the try-catch is belt-and-braces for a missing bean in a
     * narrow test context. The reason text never carries an identifier — the
     * raw body already holds whatever the sender sent, and copying an MRN into
     * a second column buys nothing.
     */
    private void recordReject(String integrationId, UUID organizationId,
                              String rawMessageBody, String reason) {
        if (messageRecorder == null) {
            return;
        }
        try {
            messageRecorder.recordMessage(
                integrationId, organizationId,
                IntegrationMessageDirection.INBOUND,
                MESSAGE_TYPE, rawMessageBody,
                IntegrationMessageStatus.FAILED, reason);
        } catch (RuntimeException ex) {
            log.warn("MLLP A40 message recorder threw for integration={} reason={}",
                integrationId, reason, ex);
        }
    }

    /**
     * {@code integration_message_event.integration_id} is
     * {@code VARCHAR(120) NOT NULL}, and HL7 v2.5 permits 180 characters in
     * each of MSH-3 and MSH-4. Truncate for the same reason
     * {@code Hl7MessageDispatcher.integrationIdFor} does: an over-long id
     * fails the recorder insert, the recorder swallows it, and the DLQ row
     * disappears — which on this path is the only surviving record of the
     * rejection.
     */
    private static final int RECORDER_INTEGRATION_ID_MAX = 120;

    private static String buildIntegrationId(String app, String fac) {
        String safeApp = StringUtils.hasText(app) ? app.trim() : "?";
        String safeFac = StringUtils.hasText(fac) ? fac.trim() : "?";
        String raw = "MLLP:" + safeApp + "/" + safeFac;
        return raw.length() > RECORDER_INTEGRATION_ID_MAX
            ? raw.substring(0, RECORDER_INTEGRATION_ID_MAX)
            : raw;
    }

    private static UUID organizationIdOf(Hospital hospital) {
        if (hospital == null || hospital.getOrganization() == null) {
            return null;
        }
        return hospital.getOrganization().getId();
    }

    /** Resolve an MRN to its patient through EMPI, or empty if unknown. */
    private Optional<UUID> resolvePatient(String mrn) {
        return empiService.findIdentityByAlias(EmpiAliasType.MRN, mrn)
            .map(EmpiIdentityResponseDTO::getPatientId)
            .filter(java.util.Objects::nonNull);
    }

    private boolean isRegisteredHere(UUID patientId, UUID hospitalId) {
        // Registration, not Patient.hospitalId: a patient may legitimately be
        // registered at several hospitals, and each of those may reconcile
        // them. Same reasoning EmpiServiceImpl.requirePatientInTenant gives.
        return registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId);
    }

    /**
     * What the merge event records about where this came from. The merge runs
     * with no principal — there is no user on an MLLP thread — so
     * {@code mergedBy} is null and this note is the only provenance the row
     * carries. Worth being specific in.
     */
    private String buildNotes(String survivingMrn, String priorMrn,
                              String sendingApplication, String sendingFacility,
                              String messageControlId) {
        return "HL7 ADT^A40 from " + sendingApplication + "/" + sendingFacility
            + ": MRN " + priorMrn + " merged into " + survivingMrn
            + (StringUtils.hasText(messageControlId)
                ? " (MSH-10 " + messageControlId + ")" : "");
    }
}
