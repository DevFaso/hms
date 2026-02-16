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

        Encounter enc = entity.getEncounter();
        Treatment treatment = entity.getTreatment();
        Staff staff = entity.getStaff();

        return EncounterTreatmentResponseDTO.builder()
            .id(entity.getId())
            .encounterId(enc != null ? enc.getId() : null)
            .encounterCode(enc != null ? enc.getCode() : null)
            .encounterType(enc != null ? enc.getEncounterType() : null)
            .patientId(resolvePatientId(enc))
            .patientFullName(resolvePatientFullName(enc))
            .patientPhoneNumber(resolvePatientPhone(enc))
            .treatmentId(treatment != null ? treatment.getId() : null)
            .treatmentName(treatment != null ? treatment.getName() : null)
            .staffId(staff != null ? staff.getId() : null)
            .staffFullName(resolveStaffFullName(staff))
            .performedAt(entity.getPerformedAt())
            .outcome(entity.getOutcome())
            .notes(entity.getNotes())
            .build();
    }

    /* ---------------- helpers ---------------- */

    private java.util.UUID resolvePatientId(Encounter enc) {
        return enc != null && enc.getPatient() != null ? enc.getPatient().getId() : null;
    }

    private String resolvePatientFullName(Encounter enc) {
        if (enc == null || enc.getPatient() == null) return null;
        return joinName(enc.getPatient().getFirstName(), enc.getPatient().getLastName());
    }

    private String resolvePatientPhone(Encounter enc) {
        return enc != null && enc.getPatient() != null ? enc.getPatient().getPhoneNumberPrimary() : null;
    }

    private String resolveStaffFullName(Staff staff) {
        if (staff != null && staff.getUser() != null) {
            return joinName(staff.getUser().getFirstName(), staff.getUser().getLastName());
        }
        return staff != null ? staff.getName() : null;
    }

    public String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? null : full;
    }
}
