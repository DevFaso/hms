package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.payload.dto.EncounterTreatmentRequestDTO;
import com.example.hms.payload.dto.EncounterTreatmentResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class EncounterTreatmentMapper {

    public EncounterTreatment toEntity(EncounterTreatmentRequestDTO dto, Encounter encounter, Treatment treatment, Staff staff) {
        if (dto == null) return null;
        return EncounterTreatment.builder()
            .encounter(encounter)
            .treatment(treatment)
            .staff(staff)
            .performedAt(dto.getPerformedAt())
            .outcome(dto.getOutcome())
            .notes(dto.getNotes())
            .build();
    }

    public EncounterTreatmentResponseDTO toDto(EncounterTreatment entity) {
        if (entity == null) return null;

        String staffFullName = null;
        if (entity.getStaff() != null && entity.getStaff().getUser() != null) {
            String first = entity.getStaff().getUser().getFirstName();
            String last  = entity.getStaff().getUser().getLastName();
            staffFullName = joinName(first, last);
        } else if (entity.getStaff() != null) {
            // fallback if Staff has its own name field
            staffFullName = entity.getStaff().getName();
        }

        return EncounterTreatmentResponseDTO.builder()
            .id(entity.getId())
            .encounterId(entity.getEncounter() != null ? entity.getEncounter().getId() : null)
            .encounterCode(entity.getEncounter() != null ? entity.getEncounter().getCode() : null)
            .encounterType(entity.getEncounter() != null ? entity.getEncounter().getEncounterType() : null)
            .patientId(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? entity.getEncounter().getPatient().getId() : null)
            .patientFullName(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? joinName(entity.getEncounter().getPatient().getFirstName(),
                entity.getEncounter().getPatient().getLastName())
                : null)
            .patientPhoneNumber(entity.getEncounter() != null && entity.getEncounter().getPatient() != null
                ? entity.getEncounter().getPatient().getPhoneNumberPrimary() : null)
            .treatmentId(entity.getTreatment() != null ? entity.getTreatment().getId() : null)
            .treatmentName(entity.getTreatment() != null ? entity.getTreatment().getName() : null)
            .staffId(entity.getStaff() != null ? entity.getStaff().getId() : null)
            .staffFullName(staffFullName)
            .performedAt(entity.getPerformedAt())
            .outcome(entity.getOutcome())
            .notes(entity.getNotes())
            .build();
    }

    /* ---------------- helpers ---------------- */

    public String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
