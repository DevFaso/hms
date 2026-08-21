package com.example.hms.mapper;

import com.example.hms.enums.PatientStayStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.payload.dto.RegistrationDeskRow;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
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

        final Patient p = resolvePatient(entity);
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

    /**
     * Maps a desk-list projection row.
     *
     * <p>Unlike the entity overload this cannot fail on bad data: the row already
     * holds plain columns, so an orphaned {@code patient_id} or {@code user_id}
     * arrives as nulls instead of throwing while Hibernate resolves the
     * association. See {@link RegistrationDeskRow} for why the desk list projects
     * rather than fetches.
     */
    public PatientHospitalRegistrationResponseDTO toDeskResponseDTO(RegistrationDeskRow row) {
        if (row == null) return null;

        return PatientHospitalRegistrationResponseDTO.builder()
            .id(row.id())
            .mri(row.mrn())
            // Patient — every field nullable: an orphaned registration still lists.
            .patientId(row.patientId())
            .patientUsername(safeTrim(row.patientUsername()))
            .patientFirstName(safeTrim(row.patientFirstName()))
            .patientLastName(safeTrim(row.patientLastName()))
            .patientEmail(row.patientEmail())
            .patientPhone(row.patientPhone())
            .patientGender(row.patientGender())
            // Hospital
            .hospitalId(row.hospitalId())
            .hospitalName(row.hospitalName())
            .hospitalCode(row.hospitalCode())
            .hospitalAddress(row.hospitalAddress())
            // Registration
            .registrationDate(row.registrationDate())
            .active(Boolean.TRUE.equals(row.active()))
            .stayStatus(row.stayStatus())
            .stayStatusUpdatedAt(row.stayStatusUpdatedAt())
            .currentRoom(row.currentRoom())
            .currentBed(row.currentBed())
            .attendingPhysicianName(row.attendingPhysicianName())
            .readyForDischargeNote(row.readyForDischargeNote())
            .readyByStaffId(row.readyByStaffId())
            .build();
    }

    /* ---------------- helpers ---------------- */

    /**
     * Resolve the registration's patient without letting an orphaned row take
     * down the read.
     *
     * <p>{@code PatientHospitalRegistration.patient} is LAZY and
     * {@code optional = false}, so {@code getPatient()} returns a proxy that is
     * never null — every {@code p != null} branch below was unreachable for a
     * dangling {@code patient_id}, and the first real getter threw instead. The
     * schema carries no foreign keys, so dangling is a state that occurs: one
     * such row 500'd the entire registrations desk in production.
     *
     * <p>The desk list no longer comes through here at all (it projects columns —
     * see {@link RegistrationDeskRow}), but the single-registration reads still
     * map entities, and one broken row should degrade to a row without patient
     * details rather than to an error.
     */
    private static Patient resolvePatient(PatientHospitalRegistration entity) {
        try {
            Patient p = entity.getPatient();
            if (p == null) {
                return null;
            }
            // Force initialisation inside the guard: the throw we care about
            // happens on first dereference, not on getPatient().
            Hibernate.initialize(p);
            return p;
        } catch (RuntimeException ex) {
            log.warn("Could not resolve patient for registration {} — rendering without patient details: {}",
                entity.getId(), ex.getMessage());
            return null;
        }
    }

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
