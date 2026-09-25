package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.integration.message.MllpRecordingContext;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundAdtServiceImpl implements MllpInboundAdtService {

    private final EmpiService empiService;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final MllpInboundAdtVisitProjectionService visitProjection;
    // Last so existing positional constructor calls only append.
    private final IntegrationMessageRecorder messageRecorder;

    @Override
    @Transactional
    public MllpInboundOutcome processAdt(ParsedAdtMessage parsed,
                                         Hospital receivingHospital,
                                         String sendingApplication,
                                         String sendingFacility) {
        return processAdt(parsed, receivingHospital, sendingApplication, sendingFacility, null);
    }

    @Override
    @Transactional
    public MllpInboundOutcome processAdt(ParsedAdtMessage parsed,
                                         Hospital receivingHospital,
                                         String sendingApplication,
                                         String sendingFacility,
                                         String messageControlId) {
        if (parsed == null || !StringUtils.hasText(parsed.mrn())) {
            log.warn("MLLP ADT rejected — missing PID-3 MRN (sender={}/{} hospital={})",
                sendingApplication, sendingFacility,
                receivingHospital != null ? receivingHospital.getId() : null);
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (receivingHospital == null || receivingHospital.getId() == null) {
            log.warn("MLLP ADT rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        String mrn = parsed.mrn().trim();
        Optional<EmpiIdentityResponseDTO> identity =
            empiService.findIdentityByAlias(EmpiAliasType.MRN, mrn);
        if (identity.isEmpty() || identity.get().getPatientId() == null) {
            // No MRN in the log line: PID-3 is PHI wherever the log ends up.
            log.warn("MLLP ADT rejected — PID-3 unknown to EMPI (sender={}/{} hospital={} event={})",
                sendingApplication, sendingFacility,
                receivingHospital.getId(), parsed.triggerEvent());
            // RECORDED, but not as a dead letter. A partner feed naming
            // patients we have not been told about is a normal condition on
            // this path — ADT never auto-provisions — and one actionable
            // FAILED row per message would bury the rejections that do need
            // somebody. The reason is still on the row, and still absent from
            // the ACK, so this changes nothing a sender can observe.
            recordReject(parsed, receivingHospital, sendingApplication, sendingFacility,
                messageControlId, "PID-3 not found", IntegrationMessageStatus.RECEIVED);
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        UUID patientId = identity.get().getPatientId();
        // PatientRepository.findById() is tenant-scoped via
        // TenantAwareJpaRepository and would return empty in this MLLP
        // path because no HospitalContext is set on the worker thread.
        // findByIdUnscoped bypasses that filter; the cross-tenant
        // PatientHospitalRegistration gate below is what actually
        // enforces "this sender is allowed to update this patient".
        Optional<Patient> patientOpt = patientRepository.findByIdUnscoped(patientId);
        if (patientOpt.isEmpty()) {
            // EMPI has the alias but the patient row is gone — data
            // inconsistency, treat as not-found rather than crashing.
            log.warn("MLLP ADT — PID-3 resolved to patientId={} but no Patient row exists",
                patientId);
            // FAILED, unlike the plain unknown MRN above: an alias with no
            // patient behind it is our own data inconsistency, and somebody
            // has to look at it.
            recordReject(parsed, receivingHospital, sendingApplication, sendingFacility,
                messageControlId, "EMPI alias without a patient row",
                IntegrationMessageStatus.FAILED);
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }
        Patient patient = patientOpt.get();

        // Cross-tenant gate: the allowlisted hospital must already have
        // this patient registered. Reject otherwise — a sender at
        // hospital B cannot push demographic updates for a patient who
        // is only known to hospital A.
        //
        // The rejection is REJECTED_NOT_FOUND, exactly what an MRN no
        // hospital has ever heard of returns, and the dispatcher builds
        // one ACK for both. It used to be REJECTED_CROSS_TENANT → AR,
        // and the difference between that AR and this AE was a read
        // primitive: an allowlisted sender could send one A08 per
        // candidate MRN and collect the MRNs that exist in hospitals it
        // cannot see. Same fix the ORU^R01 path took in #715.
        boolean registered = registrationRepository
            .findByPatientIdAndHospitalId(patient.getId(), receivingHospital.getId())
            .isPresent();
        if (!registered) {
            log.warn("MLLP ADT cross-tenant reject — patient={} not registered at hospital={} "
                + "(sender={}/{})",
                patient.getId(), receivingHospital.getId(),
                sendingApplication, sendingFacility);
            recordReject(parsed, receivingHospital, sendingApplication, sendingFacility,
                messageControlId, "cross-tenant rejection", IntegrationMessageStatus.FAILED);
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        boolean changed = applyDemographics(patient, parsed);
        if (changed) {
            patientRepository.save(patient);
            log.info("MLLP ADT applied — patient={} event={} sender={}/{} hospital={}",
                patient.getId(), parsed.triggerEvent(),
                sendingApplication, sendingFacility, receivingHospital.getId());
        } else {
            log.info("MLLP ADT no-op — patient={} event={} (no demographic changes) sender={}/{} hospital={}",
                patient.getId(), parsed.triggerEvent(),
                sendingApplication, sendingFacility, receivingHospital.getId());
        }

        // Visit-sync projection runs AFTER the demographic write but
        // BEFORE the outer @Transactional commits. The projection
        // implementation uses REQUIRES_NEW and swallows its own
        // failures so it can never roll back the demographic write —
        // see the conflict-resolution runbook for the precedence
        // rationale. Guarded here as belt-and-braces in case the
        // projection bean throws before reaching its own
        // try/transaction boundary.
        try {
            visitProjection.projectVisit(
                parsed, patient, receivingHospital,
                sendingApplication, sendingFacility, messageControlId);
        } catch (RuntimeException ex) {
            // Note: at this point demographics have been WRITTEN to
            // the JDBC connection but the outer @Transactional commit
            // happens after this method returns. The ACK we send back
            // reflects that the outer transaction is expected to
            // commit — the projection failure does not block that.
            log.warn("ADT visit-sync projection threw for patient={} hospital={} event={} sender={}/{} — demographics already written; ACK will still be sent",
                patient.getId(), receivingHospital.getId(), parsed.triggerEvent(),
                sendingApplication, sendingFacility, ex);
        }

        return MllpInboundOutcome.ACCEPTED;
    }

    /**
     * The rejection reason the ACK is not allowed to carry, written where the
     * sender cannot read it.
     *
     * <p><b>The message body is deliberately not stored.</b> The recorder will
     * take up to 64 KB of payload and the dispatcher's own parse-failure rows
     * use it, but a refusal on this path is reached once per probe by exactly
     * the sender this change is defending against — storing a full PID
     * (MRN, name, date of birth, address) per attempt, unencrypted, would turn
     * the compensating control into an unbounded PHI sink. MSH-10 is the
     * sender's own message id, not patient data, and is what an operator needs
     * to correlate the refusal with the sender's queue. Such a row is not
     * replayable, which is correct: a cross-tenant message must not be
     * replayed, it must be reconfigured.
     *
     * <p>{@code status} is the caller's judgement about whether an operator
     * has to act, and it is the only thing separating a dead letter from a
     * note: {@code FAILED} is what
     * {@code IntegrationMessageEventRepository.countUnresolvedDeadLetters}
     * counts and what the super-admin badge shows, {@code RECEIVED} records
     * the same reason without demanding attention. Nothing about the choice
     * reaches the ACK, so it cannot be read by the sender.
     *
     * <p>Best-effort: the recorder is {@code REQUIRES_NEW} and swallows its
     * own exceptions, so the row survives this transaction rolling back.
     */
    private void recordReject(ParsedAdtMessage parsed, Hospital receivingHospital,
                              String sendingApplication, String sendingFacility,
                              String messageControlId, String reason,
                              IntegrationMessageStatus status) {
        try {
            messageRecorder.recordMessage(
                MllpRecordingContext.integrationId(sendingApplication, sendingFacility),
                MllpRecordingContext.organizationId(receivingHospital),
                IntegrationMessageDirection.INBOUND,
                messageTypeOf(parsed),
                null,
                status,
                withControlId(reason, messageControlId));
        } catch (RuntimeException ex) {
            log.warn("MLLP ADT message recorder threw for sender={}/{} reason={}",
                sendingApplication, sendingFacility, reason, ex);
        }
    }

    private static String withControlId(String reason, String messageControlId) {
        return StringUtils.hasText(messageControlId)
            ? reason + " (MSH-10 " + messageControlId.trim() + ")"
            : reason;
    }

    private static String messageTypeOf(ParsedAdtMessage parsed) {
        String trigger = parsed == null ? null : parsed.triggerEvent();
        return StringUtils.hasText(trigger) ? "ADT^" + trigger.trim() : "ADT";
    }

    /**
     * Applies non-blank PID fields to the patient and returns whether
     * anything actually changed. Blank inbound fields are ignored —
     * ADT messages routinely omit fields the sender doesn't own, and
     * we don't want to wipe local data because of an under-populated
     * upstream record.
     */
    private boolean applyDemographics(Patient patient, ParsedAdtMessage parsed) {
        boolean changed = false;
        changed |= setIfChanged(parsed.lastName(), patient.getLastName(), patient::setLastName);
        changed |= setIfChanged(parsed.firstName(), patient.getFirstName(), patient::setFirstName);
        changed |= setIfChanged(parsed.middleName(), patient.getMiddleName(), patient::setMiddleName);
        if (parsed.dateOfBirth() != null
            && (patient.getDateOfBirth() == null
                || !patient.getDateOfBirth().equals(parsed.dateOfBirth()))) {
            patient.setDateOfBirth(parsed.dateOfBirth());
            changed = true;
        }
        changed |= setIfChanged(parsed.sex(), patient.getGender(), patient::setGender);
        changed |= setIfChanged(parsed.addressLine1(), patient.getAddressLine1(), patient::setAddressLine1);
        changed |= setIfChanged(parsed.city(), patient.getCity(), patient::setCity);
        changed |= setIfChanged(parsed.state(), patient.getState(), patient::setState);
        changed |= setIfChanged(parsed.zipCode(), patient.getZipCode(), patient::setZipCode);
        changed |= setIfChanged(parsed.country(), patient.getCountry(), patient::setCountry);
        return changed;
    }

    private boolean setIfChanged(String inbound, String current,
                                 java.util.function.Consumer<String> setter) {
        if (!StringUtils.hasText(inbound)) return false;
        String trimmed = inbound.trim();
        if (trimmed.equals(current)) return false;
        setter.accept(trimmed);
        return true;
    }
}
