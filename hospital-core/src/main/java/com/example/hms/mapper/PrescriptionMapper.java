package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import org.springframework.stereotype.Component;

@Component
public class PrescriptionMapper {

    /* ============================
       Response mapping
       ============================ */
    public PrescriptionResponseDTO toResponseDTO(Prescription p) {
        if (p == null) return null;

        Patient patient = p.getPatient();
        Staff   staff   = p.getStaff();
        Encounter enc   = p.getEncounter();

        String patientFullName = buildFullName(
            patient != null ? patient.getFirstName() : null,
            patient != null ? patient.getLastName()  : null
        );

        String staffFullName =
            (staff != null && notBlank(staff.getName()))
                ? staff.getName()
                : buildFullName(
                staff != null && staff.getUser() != null ? staff.getUser().getFirstName() : null,
                staff != null && staff.getUser() != null ? staff.getUser().getLastName()  : null
            );

        return PrescriptionResponseDTO.builder()
            .id(p.getId())

            .patientId(patient != null ? patient.getId() : null)
            .patientFullName(patientFullName)
            .patientEmail(patient != null ? nullSafe(patient.getEmail()) : "")

            .staffId(staff != null ? staff.getId() : null)
            .staffFullName(staffFullName)

            .encounterId(enc != null ? enc.getId() : null)
            .hospitalId(enc != null && enc.getHospital() != null ? enc.getHospital().getId() : null)

            .medicationName(p.getMedicationName())
            .medicationDisplayName(p.getMedicationDisplayName())

            .dosage(p.getDosage())
            .frequency(p.getFrequency())
            .duration(p.getDuration())
            .notes(p.getNotes())

            .status(p.getStatus() != null ? p.getStatus().name() : null)

            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .build();
    }

    /* ============================
       Entity mapping
       ============================ */
    public Prescription toEntity(PrescriptionRequestDTO dto, Patient patient, Staff staff, Encounter encounter) {
        Prescription e = new Prescription();
        e.setPatient(patient);
        e.setStaff(staff);
        e.setEncounter(encounter);
        if (encounter != null && encounter.getHospital() != null) {
            e.setHospital(encounter.getHospital());
        } else if (staff != null) {
            e.setHospital(staff.getHospital());
        }

        e.setMedicationName(dto.getMedicationName());
        e.setMedicationDisplayName(dto.getMedicationName());
        e.setMedicationDisplayName(dto.getMedicationName());

        e.setDosage(dto.getDosage());
        e.setFrequency(dto.getFrequency());
        e.setDuration(dto.getDuration());
        e.setNotes(dto.getNotes());

        return e;
    }

    public void updateEntity(Prescription target, PrescriptionRequestDTO dto,
                             Patient patient, Staff staff, Encounter encounter) {
        target.setPatient(patient);
        target.setStaff(staff);
        target.setEncounter(encounter);
        if (encounter != null && encounter.getHospital() != null) {
            target.setHospital(encounter.getHospital());
        } else if (staff != null) {
            target.setHospital(staff.getHospital());
        }

        target.setMedicationName(dto.getMedicationName());
        if (!notBlank(target.getMedicationDisplayName())) {
            target.setMedicationDisplayName(dto.getMedicationName());
        }

        target.setDosage(dto.getDosage());
        target.setFrequency(dto.getFrequency());
        target.setDuration(dto.getDuration());
        target.setNotes(dto.getNotes());
    }

    /* ============================
       Helpers
       ============================ */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String buildFullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? "" : full;
    }
}
