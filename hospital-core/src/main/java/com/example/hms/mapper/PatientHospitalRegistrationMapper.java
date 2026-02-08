package com.example.hms.mapper;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class PatientHospitalRegistrationMapper {

    public PatientHospitalRegistration toEntity(
        PatientHospitalRegistrationRequestDTO dto,
        Patient patient,
        Hospital hospital
    ) {
        if (dto == null) return null;

        return PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .active(dto.isActive())
            .registrationDate(LocalDate.now())
        .currentRoom(safeTrim(dto.getCurrentRoom()))
        .currentBed(safeTrim(dto.getCurrentBed()))
        .attendingPhysicianName(safeTrim(dto.getAttendingPhysicianName()))
            .stayStatus(dto.getStayStatus() != null ? dto.getStayStatus() : PatientStayStatus.ADMITTED)
        .readyForDischargeNote(safeTrim(dto.getReadyForDischargeNote()))
            .build();
    }

    public PatientHospitalRegistrationResponseDTO toResponseDTO(PatientHospitalRegistration entity) {
        if (entity == null) return null;

        final Patient p = entity.getPatient();
        final Hospital h = entity.getHospital();

        final String pFirst = p != null ? safeTrim(p.getFirstName()) : null;
        final String pLast  = p != null ? safeTrim(p.getLastName())  : null;

        return PatientHospitalRegistrationResponseDTO.builder()
            .id(entity.getId())
            .mri(entity.getMrn())
            // Patient
            .patientId(p != null ? p.getId() : null)
            .patientUsername(
                (p != null && p.getUser() != null && p.getUser().getUsername() != null)
                    ? p.getUser().getUsername()
                    : null
            )
            .patientFirstName(pFirst)
            .patientLastName(pLast)
            .patientEmail(p != null ? p.getEmail() : null)
            .patientPhone(p != null ? p.getPhoneNumberPrimary() : null)
            .patientGender(p != null ? p.getGender() : null)
            // Hospital
            .hospitalId(h != null ? h.getId() : null)
            .hospitalName(h != null ? h.getName() : null)
            .hospitalCode(h != null ? h.getCode() : null)
            .hospitalAddress(h != null ? h.getAddress() : null)
            // Registration
            .registrationDate(entity.getRegistrationDate())
            .active(entity.isActive())
            .stayStatus(entity.getStayStatus())
            .stayStatusUpdatedAt(entity.getStayStatusUpdatedAt())
            .currentRoom(entity.getCurrentRoom())
            .currentBed(entity.getCurrentBed())
            .attendingPhysicianName(entity.getAttendingPhysicianName())
            .readyForDischargeNote(entity.getReadyForDischargeNote())
            .readyByStaffId(entity.getReadyByStaffId())
            .build();
    }

    /* ---------------- helpers ---------------- */

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    public static String joinName(String first, String last) {
        final String f = safeTrim(first);
        final String l = safeTrim(last);
        final String full = ((f == null ? "" : f) + " " + (l == null ? "" : l)).trim();
        return full.isEmpty() ? null : full;
    }
}
