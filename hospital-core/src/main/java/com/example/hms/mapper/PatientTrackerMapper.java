package com.example.hms.mapper;

import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.clinical.PatientTrackerItemDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Hand-written mapper: Encounter → PatientTrackerItemDTO (MVP 5).
 */
@Component
public class PatientTrackerMapper {

    /**
     * Map an Encounter (with eagerly-loaded associations) to a tracker item.
     *
     * @param enc        the encounter entity (patient, staff, department, appointment expected to be loaded)
     * @param hospitalId used to resolve the patient MRN for this hospital
     * @param now        the "current time" used for wait-time calculation (injectable for testing)
     * @return a populated PatientTrackerItemDTO
     */
    public PatientTrackerItemDTO toTrackerItem(Encounter enc, UUID hospitalId, LocalDateTime now) {
        if (enc == null) return null;

        Patient patient = enc.getPatient();
        Staff staff = enc.getStaff();
        Department department = enc.getDepartment();
        Appointment appointment = enc.getAppointment();

        String patientName = patient != null
                ? nullSafe(patient.getFullName())
                : "Unknown";

        String mrn = patient != null ? patient.getMrnForHospital(hospitalId) : null;

        String providerName = null;
        if (staff != null && staff.getUser() != null) {
            providerName = (nullSafe(staff.getUser().getFirstName()) + " " + nullSafe(staff.getUser().getLastName())).trim();
        }

        String deptName = department != null ? department.getName() : null;

        String acuity = deriveAcuityLevel(enc);

        long waitMinutes = computeWaitMinutes(enc, now);

        Boolean preChecked = appointment != null ? appointment.getPreCheckedIn() : null;

        return PatientTrackerItemDTO.builder()
                .patientId(patient != null ? patient.getId() : null)
                .patientName(patientName)
                .mrn(mrn)
                .appointmentId(appointment != null ? appointment.getId() : null)
                .encounterId(enc.getId())
                .currentStatus(enc.getStatus() != null ? enc.getStatus().name() : null)
                .roomAssignment(enc.getRoomAssignment())
                .assignedProvider(providerName)
                .assignedProviderUserId(staff != null && staff.getUser() != null ? staff.getUser().getId() : null)
                .departmentName(deptName)
                .arrivalTimestamp(enc.getArrivalTimestamp())
                .triageTimestamp(enc.getTriageTimestamp())
                .currentWaitMinutes(waitMinutes)
                .acuityLevel(acuity)
                .preCheckedIn(preChecked)
                .build();
    }

    /* ── Helpers ─────────────────────────────────────────── */

    private String deriveAcuityLevel(Encounter enc) {
        if (enc.getEsiScore() != null) {
            return "ESI-" + enc.getEsiScore();
        }
        if (enc.getUrgency() != null) {
            return enc.getUrgency().name();
        }
        return "ROUTINE";
    }

    private long computeWaitMinutes(Encounter enc, LocalDateTime now) {
        // QA D1039: the one reading of a wait (arrival, else when it was recorded).
        return com.example.hms.utility.EncounterWait.minutes(enc, now);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
