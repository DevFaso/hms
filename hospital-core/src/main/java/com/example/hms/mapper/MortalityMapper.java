package com.example.hms.mapper;

import com.example.hms.model.DeathRecord;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.mortality.DeathRecordResponseDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Entity to DTO for the mortality module (Tier 2 item 29).
 *
 * <p>{@code whoMaternalDeath} is derived on READ rather than stored — the same
 * stance NEWS2 took in V130. A stored classification is one that goes stale the
 * moment an amendment changes the timing.
 */
@Component
public class MortalityMapper {

    public DeathRecordResponseDTO toDto(DeathRecord deathRecord) {
        if (deathRecord == null) {
            return null;
        }
        Patient patient = deathRecord.getPatient();
        // MRN is per-hospital, so it resolves against the certifying facility
        // rather than being read off the patient as a single global identifier.
        UUID hospitalId = deathRecord.getHospital() != null ? deathRecord.getHospital().getId() : null;

        return DeathRecordResponseDTO.builder()
            .id(deathRecord.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .patientMrn(patient != null && hospitalId != null
                ? patient.getMrnForHospital(hospitalId) : null)
            .patientDateOfBirth(patient != null ? patient.getDateOfBirth() : null)
            .hospitalId(hospitalId)
            .diedAt(deathRecord.getDiedAt())
            .placeOfDeath(deathRecord.getPlaceOfDeath())
            .mannerOfDeath(deathRecord.getMannerOfDeath())
            .immediateCause(deathRecord.getImmediateCause())
            .immediateCauseCode(deathRecord.getImmediateCauseCode())
            .underlyingCause(deathRecord.getUnderlyingCause())
            .underlyingCauseCode(deathRecord.getUnderlyingCauseCode())
            .contributingCauses(deathRecord.getContributingCauses())
            .maternalDeath(deathRecord.getMaternalDeath())
            .maternalDeathTiming(deathRecord.getMaternalDeathTiming())
            .whoMaternalDeath(deathRecord.isWhoMaternalDeath())
            .perinatalDeath(deathRecord.getPerinatalDeath())
            .perinatalType(deathRecord.getPerinatalType())
            .autopsyRequested(deathRecord.getAutopsyRequested())
            .certifiedByName(staffName(deathRecord.getCertifiedBy()))
            .certifiedAt(deathRecord.getCertifiedAt())
            .amended(deathRecord.isAmended())
            .amendedAt(deathRecord.getAmendedAt())
            .amendmentReason(deathRecord.getAmendmentReason())
            .notes(deathRecord.getNotes())
            .recordedByName(staffName(deathRecord.getRecordedBy()))
            .createdAt(deathRecord.getCreatedAt())
            .build();
    }

    private String staffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        String full = staff.getFullName();
        return full != null && !full.isBlank() ? full : staff.getName();
    }
}
