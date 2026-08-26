package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundMergeService;
import com.example.hms.service.integration.MllpInboundOutcome;
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

    private final EmpiService empiService;
    private final PatientHospitalRegistrationRepository registrationRepository;

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

        if (survivingMrn.equalsIgnoreCase(priorMrn)) {
            // Not an error worth alarming about, but not a merge either.
            log.warn("MLLP A40 rejected — PID-3 and MRG-1 are the same identifier ({}) "
                + "sender={}/{} hospital={}",
                survivingMrn, sendingApplication, sendingFacility, receivingHospital.getId());
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        Optional<UUID> survivor = resolvePatient(survivingMrn);
        Optional<UUID> retiree = resolvePatient(priorMrn);
        if (survivor.isEmpty() || retiree.isEmpty()) {
            // Deliberately NOT auto-provisioned. An unrecognised identifier in
            // a merge message means the two systems disagree about who exists,
            // and inventing the missing side would bake that disagreement in.
            log.warn("MLLP A40 rejected — unknown identifier(s): surviving={} known={} "
                + "prior={} known={} sender={}/{} hospital={}",
                survivingMrn, survivor.isPresent(), priorMrn, retiree.isPresent(),
                sendingApplication, sendingFacility, receivingHospital.getId());
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        UUID survivingPatientId = survivor.get();
        UUID retiringPatientId = retiree.get();

        if (survivingPatientId.equals(retiringPatientId)) {
            // Two different MRNs already resolving to one patient — the merge
            // this message asks for has effectively happened. Accepting keeps
            // a resend idempotent instead of parking a permanent AE in the
            // sender's queue for work that is already done.
            log.info("MLLP A40 no-op — {} and {} already resolve to patient {} "
                + "(sender={}/{} msgCtrlId={})",
                survivingMrn, priorMrn, survivingPatientId,
                sendingApplication, sendingFacility, messageControlId);
            return MllpInboundOutcome.ACCEPTED;
        }

        // THE GATE. EmpiServiceImpl's own tenant checks resolve the caller's
        // hospital from the security context, and there is none on this
        // thread — isVisibleToCaller reads a null active hospital as
        // "unscoped, allow". Without this, an allowlisted sender could merge
        // any two patients in the system. BOTH sides, not just one: merging a
        // stranger's record INTO a local patient is as damaging as the
        // reverse, and only checking the survivor would permit it.
        UUID hospitalId = receivingHospital.getId();
        if (!isRegisteredHere(survivingPatientId, hospitalId)
                || !isRegisteredHere(retiringPatientId, hospitalId)) {
            log.warn("MLLP A40 cross-tenant reject — surviving={} prior={} not both registered "
                + "at hospital={} (sender={}/{})",
                survivingMrn, priorMrn, hospitalId, sendingApplication, sendingFacility);
            return MllpInboundOutcome.REJECTED_CROSS_TENANT;
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
            log.warn("MLLP A40 refused by the merge service — surviving={} prior={} "
                + "sender={}/{} hospital={}: {}",
                survivingMrn, priorMrn, sendingApplication, sendingFacility,
                hospitalId, ex.getMessage());
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        log.info("MLLP A40 applied — {} merged into {} (patients {} <- {}) "
            + "sender={}/{} hospital={} msgCtrlId={}",
            priorMrn, survivingMrn, survivingPatientId, retiringPatientId,
            sendingApplication, sendingFacility, hospitalId, messageControlId);
        return MllpInboundOutcome.ACCEPTED;
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
