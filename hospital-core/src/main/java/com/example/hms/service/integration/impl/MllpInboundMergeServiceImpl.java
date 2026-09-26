package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.message.MllpRecordingContext;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.StringUtils;

/**
 * Inbound {@code ADT^A40} patient merge (Tier 2 item 41).
 *
 * <p>See {@link MllpInboundMergeService} for why this enforces the tenant
 * boundary itself and then hands EMPI the receiving hospital explicitly,
 * instead of relying on {@code EmpiServiceImpl}'s request-scoped guards, which
 * have no security context to read on an MLLP worker thread.
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
                                           String messageControlId) {
        if (parsed == null
                || !StringUtils.hasText(parsed.survivingMrn())
                || !StringUtils.hasText(parsed.priorMrn())) {
            log.warn("MLLP A40 rejected — missing PID-3 or MRG-1 (sender={}/{} hospital={})",
                sendingApplication, sendingFacility,
                receivingHospital != null ? receivingHospital.getId() : null);
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (receivingHospital == null || receivingHospital.getId() == null) {
            log.warn("MLLP A40 rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        String survivingMrn = parsed.survivingMrn().trim();
        String priorMrn = parsed.priorMrn().trim();
        // MSH-10 as every log line below shows it: the same quoting and
        // escaping as the dead-letter reason, so a sender cannot forge or
        // reorder a log line with ANSI, separator or bidi characters.
        String loggedControlId = MllpRecordingContext.quotedControlId(messageControlId);

        if (survivingMrn.equalsIgnoreCase(priorMrn)) {
            // Not an error worth alarming about, but not a merge either.
            // The identifier itself stays out of the log line: PID-3 is an
            // MRN, and an MRN in a log is PHI wherever that log ends up.
            log.warn("MLLP A40 rejected — PID-3 and MRG-1 are the same identifier "
                + "(sender={}/{} hospital={})",
                sendingApplication, sendingFacility, receivingHospital.getId());
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        UUID hospitalId = receivingHospital.getId();

        Optional<UUID> survivor = resolvePatient(survivingMrn);
        Optional<UUID> retiree = resolvePatient(priorMrn);
        if (survivor.isEmpty() || retiree.isEmpty()) {
            // Deliberately NOT auto-provisioned. An unrecognised identifier in
            // a merge message means the two systems disagree about who exists,
            // and inventing the missing side would bake that disagreement in.
            // The MRNs stay out of the log - PID-3 is PHI wherever a log
            // ends up - but which side was unknown, and MSH-10, do not. The
            // log is not sender-readable, so withholding them protects no
            // one, and without them an operator told "our merge was
            // rejected" cannot tell which message or which side.
            boolean survivorKnown = survivor.isPresent();
            boolean retireeKnown = retiree.isPresent();
            log.warn("MLLP A40 rejected — unknown identifier(s): surviving known={} "
                    + "prior known={} (sender={}/{} hospital={} msgCtrlId={})",
                survivorKnown, retireeKnown,
                sendingApplication, sendingFacility, hospitalId, loggedControlId);
            recordReject(receivingHospital, sendingApplication, sendingFacility,
                messageControlId, "identifier not found");
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        UUID survivingPatientId = survivor.get();
        UUID retiringPatientId = retiree.get();

        // THE GATE — and it runs BEFORE any other answer that depends on what
        // these two identifiers are to each other.
        //
        // EmpiServiceImpl's request-scoped checks resolve the caller's hospital
        // from the security context, and there is none on this thread. The
        // merge below is handed this hospital explicitly and EMPI re-applies
        // its own rules at it, but it is THIS gate that decides which answer a
        // sender may see, and it has to run before anything else answers.
        // BOTH sides, not just one: merging a stranger's record
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
        // Both lookups, always, before the branch. `||` would short-circuit:
        // one query when the survivor is foreign, two when the survivor is
        // local and the retiree is not - and the partial-ownership case is
        // precisely the one a sender probes, so leaving it a round-trip
        // cheaper hands back in latency what the identical ACK denies.
        //
        // This closes the smaller of two deltas on that probe, not the whole
        // of it. A candidate identifier that exists nowhere returns above
        // after two EMPI reads and never reaches these queries at all, while
        // one that exists in another hospital reaches both of them - so the
        // work still differs between "unknown" and "exists elsewhere" even
        // though the ACK does not. That is the residual MllpInboundOutcome
        // documents; it cannot be closed by reordering, because there is no
        // patient id to look up until EMPI has resolved one. What is closed
        // here is the part that was free.
        boolean survivorIsOurs = isRegisteredHere(survivingPatientId, hospitalId);
        boolean retireeIsOurs = isRegisteredHere(retiringPatientId, hospitalId);
        if (!survivorIsOurs || !retireeIsOurs) {
            // Same outcome as the unknown identifier above — same ACK code,
            // same ACK text. The reason survives on the integration message
            // row, which the sender cannot read. Nor, today, can the
            // hospital's own integration operator: the only read surface is
            // /super-admin/integration-messages (SUPER_ADMIN), so diagnosing a
            // misconfigured sender means escalating. Widening that surface is
            // a separate change.
            // Patient ids, not MRNs: an id is not PHI by itself, and it is
            // what tells an operator which merge was refused.
            log.warn("MLLP A40 cross-tenant reject — surviving={} registered={} "
                    + "prior={} registered={} at hospital={} (sender={}/{} msgCtrlId={})",
                survivingPatientId, survivorIsOurs, retiringPatientId, retireeIsOurs,
                hospitalId, sendingApplication, sendingFacility, loggedControlId);
            recordReject(receivingHospital, sendingApplication, sendingFacility,
                messageControlId, "cross-tenant rejection");
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
                hospitalId, loggedControlId);
            return MllpInboundOutcome.ACCEPTED;
        }

        try {
            // The receiving hospital, handed over explicitly: this thread has no
            // request context for EMPI to resolve one from, and mergePatients
            // refuses every merge without one. The allowlist established this
            // hospital and the gate above authorised both patients at it.
            empiService.mergePatientsAtAuthorisedHospital(
                hospitalId,
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
            //
            // The merge joined this transaction, so its exception has already
            // marked it rollback-only; left alone, the commit on the way out
            // would throw UnexpectedRollbackException and the sender would get
            // the server-error AE instead of this one. Rolling back on purpose
            // turns that into a silent rollback of everything this message did.
            rollBackThisMessage();
            // This refusal writes no integration_message_event row, so MSH-10
            // in the log is the only way to correlate it with the sender's
            // queue. It is not the only one: the missing PID-3/MRG-1,
            // no-hospital and same-identifier refusals above write no row
            // either, and their log lines do not carry MSH-10 yet.
            log.warn("MLLP A40 refused by the merge service — sender={}/{} hospital={} "
                    + "msgCtrlId={}: {}",
                sendingApplication, sendingFacility, hospitalId, loggedControlId,
                ex.getMessage());
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        log.info("MLLP A40 applied — patients {} <- {} "
            + "sender={}/{} hospital={} msgCtrlId={}",
            survivingPatientId, retiringPatientId,
            sendingApplication, sendingFacility, hospitalId, loggedControlId);
        return MllpInboundOutcome.ACCEPTED;
    }

    /**
     * The rejection reason the ACK is not allowed to carry, written where the
     * sender cannot read it.
     *
     * <p><b>The message body is deliberately not stored</b> — see the same
     * method on {@code MllpInboundAdtServiceImpl} for why: one probe, one
     * small row, no PID. MSH-10 is the sender's own message id, not patient
     * data, and is what correlates the refusal with the sender's queue.
     *
     * <p>{@code FAILED}, like every refusal on this surface, with the badge
     * kept honest by a correlation id derived from the sender, the message
     * type and the reason rather than from anything per-message — see the
     * same method on {@code MllpInboundAdtServiceImpl} for why a retried
     * refusal must supersede its own row instead of stacking a new dead
     * letter.
     *
     * <p>Best-effort: the recorder is {@code REQUIRES_NEW} and swallows its
     * own exceptions, so the row survives this transaction rolling back.
     */
    private void recordReject(Hospital receivingHospital,
                              String sendingApplication, String sendingFacility,
                              String messageControlId, String reason) {
        String integrationId =
            MllpRecordingContext.integrationId(sendingApplication, sendingFacility);
        try {
            messageRecorder.recordMessage(
                integrationId,
                MllpRecordingContext.organizationId(receivingHospital),
                IntegrationMessageDirection.INBOUND,
                MESSAGE_TYPE,
                null,
                IntegrationMessageStatus.FAILED,
                MllpRecordingContext.withControlId(reason, messageControlId),
                // senderScope, not integrationId - see the same call on
                // MllpInboundAdtServiceImpl: the id is column-clamped, and a
                // shared correlation id lets one sender's refusal supersede
                // another's in the dead-letter count.
                MllpRecordingContext.rejectionCorrelationId(
                    MllpRecordingContext.senderScope(sendingApplication, sendingFacility),
                    MESSAGE_TYPE, reason));
        } catch (RuntimeException ex) {
            log.warn("MLLP A40 message recorder threw for sender={}/{} reason={}",
                sendingApplication, sendingFacility, reason, ex);
        }
    }

    /**
     * Mark this message's transaction for rollback, as this method's own
     * decision rather than a participant's failure: Spring then rolls it back
     * quietly at the end of {@code processMerge} instead of throwing on the
     * commit, so the refusal's ACK is the one the sender receives.
     */
    private static void rollBackThisMessage() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException notInOne) {
            // Called without processMerge's transactional proxy (a unit test
            // constructing this class directly): there is nothing to roll back.
            log.debug("MLLP A40 refusal outside a transaction — nothing to roll back");
        }
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
        //
        // OPEN QUESTION, not a decision - and the same one is noted on
        // MllpInboundAdtServiceImpl's gate, so neither reads as checked:
        // this ignores `active`, so a registration that was closed (patient
        // transferred out, episode ended) still lets that hospital's sender
        // merge the patient. It has never been decided either way.
        return registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId);
    }

    /**
     * What the merge event records about where this came from. The merge runs
     * with no principal — there is no user on an MLLP thread — so
     * {@code mergedBy} is null and this note is the only provenance the row
     * carries. Worth being specific in.
     *
     * <p>The MRNs stay here, and this is the one place on this path they do.
     * They are kept out of every log line because a log is copied and
     * retained where PHI should not go; a merge event without the two
     * identifiers is unauditable. Recorded as debt: it lands in
     * {@code EmpiMergeEvent.notes}, plain {@code TEXT} with no
     * {@code EncryptedStringConverter}, and encrypting that column would
     * have to cover every existing row.
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
