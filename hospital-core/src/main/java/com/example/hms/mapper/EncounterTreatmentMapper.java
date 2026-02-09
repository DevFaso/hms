package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Patient;
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

        Encounter encounter = entity.getEncounter();
        Patient patient = (encounter != null) ? encounter.getPatient() : null;
        Treatment treatment = entity.getTreatment();
        Staff staff = entity.getStaff();

        return EncounterTreatmentResponseDTO.builder()
            .id(entity.getId())
            .encounterId(encounter != null ? encounter.getId() : null)
            .encounterCode(encounter != null ? encounter.getCode() : null)
            .encounterType(encounter != null ? encounter.getEncounterType() : null)
            .patientId(patient != null ? patient.getId() : null)
            .patientFullName(patient != null ? joinName(patient.getFirstName(), patient.getLastName()) : null)
            .patientPhoneNumber(patient != null ? patient.getPhoneNumberPrimary() : null)
            .treatmentId(treatment != null ? treatment.getId() : null)
            .treatmentName(treatment != null ? treatment.getName() : null)
            .staffId(staff != null ? staff.getId() : null)
            .staffFullName(resolveStaffName(staff))
            .performedAt(entity.getPerformedAt())
            .outcome(entity.getOutcome())
            .notes(entity.getNotes())
            .build();
    }

    private String resolveStaffName(Staff staff) {
        if (staff == null) return null;
        if (staff.getUser() != null) {
            return joinName(staff.getUser().getFirstName(), staff.getUser().getLastName());
        }
        return staff.getName();
    }

    /* ---------------- helpers ---------------- */

    public String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
