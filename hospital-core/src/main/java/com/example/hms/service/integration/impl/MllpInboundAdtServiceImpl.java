package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundAdtService;
import com.example.hms.service.integration.MllpInboundOutcome;
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

    @Override
    @Transactional
    public MllpInboundOutcome processAdt(ParsedAdtMessage parsed,
                                         Hospital receivingHospital,
                                         String sendingApplication,
                                         String sendingFacility) {
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
            log.warn("MLLP ADT mrn={} unknown to EMPI — sender={}/{} hospital={} event={}",
                mrn, sendingApplication, sendingFacility,
                receivingHospital.getId(), parsed.triggerEvent());
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        UUID patientId = identity.get().getPatientId();
        Optional<Patient> patientOpt = patientRepository.findById(patientId);
        if (patientOpt.isEmpty()) {
            // EMPI has the alias but the patient row is gone — data
            // inconsistency, treat as not-found rather than crashing.
            log.warn("MLLP ADT mrn={} resolved to patientId={} but no Patient row exists",
                mrn, patientId);
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }
        Patient patient = patientOpt.get();

        // Cross-tenant gate: the allowlisted hospital must already have
        // this patient registered. Reject otherwise — a sender at
        // hospital B cannot push demographic updates for a patient who
        // is only known to hospital A.
        boolean registered = registrationRepository
            .findByPatientIdAndHospitalId(patient.getId(), receivingHospital.getId())
            .isPresent();
        if (!registered) {
            log.warn("MLLP ADT mrn={} patient={} not registered at hospital={} (sender={}/{})",
                mrn, patient.getId(), receivingHospital.getId(),
                sendingApplication, sendingFacility);
            return MllpInboundOutcome.REJECTED_CROSS_TENANT;
        }

        boolean changed = applyDemographics(patient, parsed);
        if (changed) {
            patientRepository.save(patient);
            log.info("MLLP ADT applied — patient={} mrn={} event={} sender={}/{} hospital={}",
                patient.getId(), mrn, parsed.triggerEvent(),
                sendingApplication, sendingFacility, receivingHospital.getId());
        } else {
            log.info("MLLP ADT no-op — patient={} mrn={} event={} (no demographic changes) sender={}/{} hospital={}",
                patient.getId(), mrn, parsed.triggerEvent(),
                sendingApplication, sendingFacility, receivingHospital.getId());
        }
        return MllpInboundOutcome.ACCEPTED;
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
