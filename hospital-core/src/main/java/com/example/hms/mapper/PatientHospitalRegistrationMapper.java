package com.example.hms.mapper;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
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
            .patientUsername(resolveUsername(p))
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

    /**
     * Resolve the patient's login name without letting a bad row take down the
     * whole list.
     *
     * <p>{@code Patient.user} is LAZY, so {@code getUser() != null} passes even
     * for an uninitialised proxy — the guard it replaces was a no-op, and the
     * real database hit happened on {@code getUsername()}. When
     * {@code clinical.patients.user_id} is dangling or duplicated (the column
     * has no FK and no unique index — see V113), that call throws and the
     * caller gets a bare 500 for the entire page.
     *
     * <p>A missing username is not worth failing a desk worklist over: log it
     * and render the row with a null username instead.
     */
    private static String resolveUsername(Patient p) {
        if (p == null) {
            return null;
        }
        try {
            User user = p.getUser();
            return user == null ? null : safeTrim(user.getUsername());
        } catch (RuntimeException ex) {
            log.warn("Could not resolve user for patient {} — rendering registration without a username: {}",
                p.getId(), ex.getMessage());
            return null;
        }
    }

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
