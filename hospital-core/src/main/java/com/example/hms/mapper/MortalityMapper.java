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

    public DeathRecordResponseDTO toDto(DeathRecord record) {
        if (record == null) {
            return null;
        }
        Patient patient = record.getPatient();
        // MRN is per-hospital, so it resolves against the certifying facility
        // rather than being read off the patient as a single global identifier.
        UUID hospitalId = record.getHospital() != null ? record.getHospital().getId() : null;

        return DeathRecordResponseDTO.builder()
            .id(record.getId())
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .patientMrn(patient != null && hospitalId != null
                ? patient.getMrnForHospital(hospitalId) : null)
            .patientDateOfBirth(patient != null ? patient.getDateOfBirth() : null)
            .hospitalId(hospitalId)
            .diedAt(record.getDiedAt())
            .placeOfDeath(record.getPlaceOfDeath())
            .mannerOfDeath(record.getMannerOfDeath())
            .immediateCause(record.getImmediateCause())
            .immediateCauseCode(record.getImmediateCauseCode())
            .underlyingCause(record.getUnderlyingCause())
            .underlyingCauseCode(record.getUnderlyingCauseCode())
            .contributingCauses(record.getContributingCauses())
            .maternalDeath(record.getMaternalDeath())
            .maternalDeathTiming(record.getMaternalDeathTiming())
            .whoMaternalDeath(record.isWhoMaternalDeath())
            .perinatalDeath(record.getPerinatalDeath())
            .perinatalType(record.getPerinatalType())
            .autopsyRequested(record.getAutopsyRequested())
            .certifiedByName(staffName(record.getCertifiedBy()))
            .certifiedAt(record.getCertifiedAt())
            .amended(record.isAmended())
            .amendedAt(record.getAmendedAt())
            .amendmentReason(record.getAmendmentReason())
            .notes(record.getNotes())
            .recordedByName(staffName(record.getRecordedBy()))
            .createdAt(record.getCreatedAt())
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
