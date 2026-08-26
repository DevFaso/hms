package com.example.hms.mapper;

import com.example.hms.enums.AboGroup;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.Staff;
import com.example.hms.model.TransfusionAdministration;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionReaction;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.payload.dto.transfusion.BloodUnitResponseDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchResponseDTO;
import com.example.hms.payload.dto.transfusion.PatientBloodGroupResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionAdministrationResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionReactionResponseDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Entity to DTO for the transfusion module (Tier 2 item 28).
 *
 * <p>Derived flags — {@code screenCurrent}, {@code expired}, {@code usable},
 * {@code severe} — are computed on READ rather than stored, the stance NEWS2
 * took in V130. A unit does not become expired when somebody looks at it, and a
 * stored staleness flag is a staleness bug waiting to happen.
 */
@Component
@RequiredArgsConstructor
public class TransfusionMapper {

    /**
     * The derived flags below — screenCurrent, expired, usable — are all
     * "is this still valid NOW" questions, so the clock is injected here too
     * rather than read inline.
     */
    private final Clock clock;

    public PatientBloodGroupResponseDTO toDto(PatientBloodGroup group) {
        if (group == null) {
            return null;
        }
        return PatientBloodGroupResponseDTO.builder()
            .id(group.getId())
            .patientId(group.getPatient() != null ? group.getPatient().getId() : null)
            .patientName(patientName(group.getPatient()))
            .hospitalId(group.getHospital() != null ? group.getHospital().getId() : null)
            .aboGroup(group.getAboGroup())
            .rhFactor(group.getRhFactor())
            .antibodyScreen(group.getAntibodyScreen())
            .antibodyDetail(group.getAntibodyDetail())
            .specimenCollectedAt(group.getSpecimenCollectedAt())
            .performedAt(group.getPerformedAt())
            .expiresAt(group.getExpiresAt())
            .performedByName(staffName(group.getPerformedBy()))
            .superseded(group.getSuperseded())
            .screenCurrent(group.screenIsCurrent(LocalDateTime.now(clock)))
            .notes(group.getNotes())
            .createdAt(group.getCreatedAt())
            .build();
    }

    public BloodUnitResponseDTO toDto(BloodUnit unit) {
        if (unit == null) {
            return null;
        }
        return BloodUnitResponseDTO.builder()
            .id(unit.getId())
            .hospitalId(unit.getHospital() != null ? unit.getHospital().getId() : null)
            .requestId(unit.getRequest() != null ? unit.getRequest().getId() : null)
            .unitNumber(unit.getUnitNumber())
            .productType(unit.getProductType())
            .aboGroup(unit.getAboGroup())
            .rhFactor(unit.getRhFactor())
            .volumeMl(unit.getVolumeMl())
            .collectedOn(unit.getCollectedOn())
            .expiresOn(unit.getExpiresOn())
            .expired(unit.isExpiredOn(LocalDate.now(clock)))
            .source(unit.getSource())
            .status(unit.getStatus())
            .discardReason(unit.getDiscardReason())
            .notes(unit.getNotes())
            .createdAt(unit.getCreatedAt())
            .build();
    }

    public CrossmatchResponseDTO toDto(TransfusionCrossmatch crossmatch) {
        if (crossmatch == null) {
            return null;
        }
        BloodUnit unit = crossmatch.getBloodUnit();
        return CrossmatchResponseDTO.builder()
            .id(crossmatch.getId())
            .requestId(crossmatch.getRequest() != null ? crossmatch.getRequest().getId() : null)
            .bloodUnitId(unit != null ? unit.getId() : null)
            .unitNumber(unit != null ? unit.getUnitNumber() : null)
            .compatible(crossmatch.getCompatible())
            .method(crossmatch.getMethod())
            .incompatibilityReason(crossmatch.getIncompatibilityReason())
            .performedByName(staffName(crossmatch.getPerformedBy()))
            .performedAt(crossmatch.getPerformedAt())
            .expiresAt(crossmatch.getExpiresAt())
            .usable(crossmatch.isUsableAt(LocalDateTime.now(clock)))
            // Advisory only — computed from the pairing, never stored, and
            // deliberately not part of compatible/usable. See the DTO field.
            .plateletPairingPendingConfirmation(pendingConfirmation(crossmatch, unit))
            .build();
    }

    /**
     * Whether this crossmatch is the O-platelets-to-a-B-recipient pairing
     * whose intent is still an open clinical question (2026-08-26).
     *
     * <p>Fails to {@code false} on missing data rather than raising a flag
     * about a pairing it cannot actually identify — the opposite of the
     * fail-closed stance {@code AboGroup} takes, and correct here: this is an
     * advisory, and a spurious one costs a clinician's attention for nothing.
     */
    private boolean pendingConfirmation(TransfusionCrossmatch crossmatch, BloodUnit unit) {
        TransfusionRequest request = crossmatch.getRequest();
        if (unit == null || request == null || request.getBloodGroup() == null) {
            return false;
        }
        return AboGroup.isPlateletPairingPendingConfirmation(
            request.getBloodGroup().getAboGroup(),
            unit.getAboGroup(),
            request.getProductType());
    }

    public TransfusionRequestResponseDTO toDto(TransfusionRequest request,
                                               List<BloodUnit> units,
                                               List<TransfusionCrossmatch> crossmatches) {
        if (request == null) {
            return null;
        }
        PatientBloodGroup group = request.getBloodGroup();
        Patient patient = request.getPatient();
        // MRN is per-hospital (a patient registered at two facilities holds
        // two), so it is resolved against THIS request's hospital rather than
        // read off the patient as if it were a single global identifier.
        UUID hospitalId = request.getHospital() != null ? request.getHospital().getId() : null;
        return TransfusionRequestResponseDTO.builder()
            .id(request.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patientName(patient))
            .patientMrn(patient != null && hospitalId != null
                ? patient.getMrnForHospital(hospitalId) : null)
            .hospitalId(hospitalId)
            .encounterId(request.getEncounter() != null ? request.getEncounter().getId() : null)
            .productType(request.getProductType())
            .unitsRequested(request.getUnitsRequested())
            .indication(request.getIndication())
            .urgency(request.getUrgency())
            .status(request.getStatus())
            .requestedByName(staffName(request.getRequestedBy()))
            .requestedAt(request.getRequestedAt())
            .requiredBy(request.getRequiredBy())
            .cancelReason(request.getCancelReason())
            .notes(request.getNotes())
            .bloodGroupId(group != null ? group.getId() : null)
            .patientAboGroup(group != null ? group.getAboGroup() : null)
            .patientRhFactor(group != null ? group.getRhFactor() : null)
            .screenCurrent(group != null && group.screenIsCurrent(LocalDateTime.now(clock)))
            .units(units == null ? List.of() : units.stream().map(this::toDto).toList())
            .crossmatches(crossmatches == null ? List.of()
                : crossmatches.stream().map(this::toDto).toList())
            .createdAt(request.getCreatedAt())
            .build();
    }

    public TransfusionAdministrationResponseDTO toDto(TransfusionAdministration administration,
                                                      List<TransfusionReaction> reactions) {
        if (administration == null) {
            return null;
        }
        BloodUnit unit = administration.getBloodUnit();
        return TransfusionAdministrationResponseDTO.builder()
            .id(administration.getId())
            .requestId(administration.getRequest() != null ? administration.getRequest().getId() : null)
            .bloodUnitId(unit != null ? unit.getId() : null)
            .unitNumber(unit != null ? unit.getUnitNumber() : null)
            .patientId(administration.getPatient() != null ? administration.getPatient().getId() : null)
            .patientName(patientName(administration.getPatient()))
            .status(administration.getStatus())
            .startedAt(administration.getStartedAt())
            .completedAt(administration.getCompletedAt())
            .volumeTransfusedMl(administration.getVolumeTransfusedMl())
            .administeredByName(staffName(administration.getAdministeredBy()))
            .verifiedByName(staffName(administration.getVerifiedBy()))
            .verificationMethod(administration.getVerificationMethod())
            .stopReason(administration.getStopReason())
            .notes(administration.getNotes())
            .reactions(reactions == null ? List.of() : reactions.stream().map(this::toDto).toList())
            .build();
    }

    public TransfusionReactionResponseDTO toDto(TransfusionReaction reaction) {
        if (reaction == null) {
            return null;
        }
        return TransfusionReactionResponseDTO.builder()
            .id(reaction.getId())
            .administrationId(reaction.getAdministration() != null ? reaction.getAdministration().getId() : null)
            .patientId(reaction.getPatient() != null ? reaction.getPatient().getId() : null)
            .patientName(patientName(reaction.getPatient()))
            .reactionType(reaction.getReactionType())
            .severity(reaction.getSeverity())
            .onsetAt(reaction.getOnsetAt())
            .signsSymptoms(reaction.getSignsSymptoms())
            .actionsTaken(reaction.getActionsTaken())
            .unitReturnedToLab(reaction.getUnitReturnedToLab())
            .reportedByName(staffName(reaction.getReportedBy()))
            .reportedAt(reaction.getReportedAt())
            .severe(reaction.isSevere())
            .build();
    }

    private String patientName(Patient patient) {
        return patient != null ? patient.getFullName() : null;
    }

    private String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String full = staff.getFullName();
        return full != null && !full.isBlank() ? full : staff.getName();
    }
}
