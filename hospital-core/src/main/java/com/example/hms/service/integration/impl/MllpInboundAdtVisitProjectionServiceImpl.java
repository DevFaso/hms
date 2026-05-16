package com.example.hms.service.integration.impl;

import com.example.hms.hl7.mllp.AdtVisitSyncProperties;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Foundation-pass implementation of the ADT visit projection.
 *
 * <p>See interface javadoc for the scope decision (reconcile-only;
 * no auto-create). The follow-on PR will introduce per-hospital
 * intake-provider config and start writing new Admission/Encounter
 * rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundAdtVisitProjectionServiceImpl
    implements MllpInboundAdtVisitProjectionService {

    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;
    private final AdtVisitSyncProperties properties;

    /**
     * Runs in a nested transaction so a projection failure cannot roll
     * back the demographic upsert in
     * {@link com.example.hms.service.integration.impl.MllpInboundAdtServiceImpl}.
     * Worst case the projection logs a warning and the demographics
     * still land — exactly the priority order documented in the
     * conflict-resolution runbook (demographics: HL7 wins;
     * Admission/Encounter clinical fields: HMS wins).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VisitProjectionResult projectVisit(
        ParsedAdtMessage parsed,
        Patient patient,
        Hospital receivingHospital,
        String sendingApplication,
        String sendingFacility,
        String messageControlId
    ) {
        if (!properties.isEnabled()) {
            return VisitProjectionResult.SKIPPED;
        }
        if (parsed == null || !StringUtils.hasText(parsed.visitNumber())) {
            // No PV1-19 to reconcile against. Common case for sites that
            // send pure demographic A08 updates with no visit context.
            return VisitProjectionResult.SKIPPED;
        }
        if (receivingHospital == null || receivingHospital.getId() == null
            || patient == null || patient.getId() == null) {
            // Caller's contract: both must already be resolved by the
            // demographic step. If they aren't, projection is not the
            // place to surface that — degrade quietly.
            return VisitProjectionResult.SKIPPED;
        }

        String visitNumber = parsed.visitNumber().trim();
        String app = trimToNull(sendingApplication);
        String fac = trimToNull(sendingFacility);
        String controlId = trimToNull(messageControlId);
        UUID hospitalId = receivingHospital.getId();

        // Admission first: A01 (admit notification) is the dominant
        // trigger that creates an inpatient record on the sender side;
        // when an admission exists it is the more specific match.
        Optional<Admission> admission = admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                app, fac, visitNumber, hospitalId);
        if (admission.isPresent()) {
            Admission row = admission.get();
            row.setExternalMessageControlId(controlId);
            admissionRepository.save(row);
            log.info("ADT visit-sync reconciled — admission={} visit={} sender={}/{} hospital={} event={} msgCtrlId={}",
                row.getId(), visitNumber, app, fac, hospitalId,
                parsed.triggerEvent(), controlId);
            return VisitProjectionResult.ADMISSION_RECONCILED;
        }

        Optional<Encounter> encounter = encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                app, fac, visitNumber, hospitalId);
        if (encounter.isPresent()) {
            Encounter row = encounter.get();
            row.setExternalMessageControlId(controlId);
            encounterRepository.save(row);
            log.info("ADT visit-sync reconciled — encounter={} visit={} sender={}/{} hospital={} event={} msgCtrlId={}",
                row.getId(), visitNumber, app, fac, hospitalId,
                parsed.triggerEvent(), controlId);
            return VisitProjectionResult.ENCOUNTER_RECONCILED;
        }

        if (properties.isLogUnmatched()) {
            // Visible during soak as "ADT would-create" workload. See
            // conflict-resolution runbook for what to do with the
            // warning (typically: provision an Admission in-app, then
            // stamp external_visit_number manually to bind future
            // updates).
            log.warn("ADT visit-sync NO_MATCH — visit={} sender={}/{} hospital={} patient={} event={} (no existing Admission or Encounter; auto-create deferred to follow-on PR)",
                visitNumber, app, fac, hospitalId, patient.getId(),
                parsed.triggerEvent());
        }
        return VisitProjectionResult.NO_MATCH;
    }

    private static String trimToNull(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
