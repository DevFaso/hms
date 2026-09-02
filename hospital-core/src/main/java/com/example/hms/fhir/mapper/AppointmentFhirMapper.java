package com.example.hms.fhir.mapper;

import com.example.hms.enums.AppointmentStatus;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * HMS appointment → FHIR R4 {@code Appointment} (Tier 2 item 43).
 *
 * <p>Read-only, like every provider here: booking goes through the slot
 * writer with its hold/version ceremony, and a FHIR write path would be a
 * way around it.
 */
@Component
public class AppointmentFhirMapper {

    public Appointment toFhir(com.example.hms.model.Appointment src) {
        if (src == null || src.getId() == null) return null;
        Appointment appt = new Appointment();
        appt.setId(src.getId().toString());
        appt.setStatus(mapStatus(src.getStatus()));
        setWindow(appt, src);
        if (src.getReason() != null && !src.getReason().isBlank()) {
            appt.setDescription(src.getReason());
        }
        addPatientParticipant(appt, src);
        addPractitionerParticipant(appt, src);
        return appt;
    }

    private static void setWindow(Appointment appt, com.example.hms.model.Appointment src) {
        if (src.getAppointmentDate() == null) {
            return;
        }
        if (src.getStartTime() != null) {
            appt.setStart(toDate(src.getAppointmentDate(), src.getStartTime()));
        }
        if (src.getEndTime() != null) {
            appt.setEnd(toDate(src.getAppointmentDate(), src.getEndTime()));
        }
    }

    private static void addPatientParticipant(Appointment appt,
                                              com.example.hms.model.Appointment src) {
        if (src.getPatient() != null && src.getPatient().getId() != null) {
            appt.addParticipant()
                .setActor(new Reference("Patient/" + src.getPatient().getId()))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        }
    }

    private static void addPractitionerParticipant(Appointment appt,
                                                   com.example.hms.model.Appointment src) {
        if (src.getStaff() == null || src.getStaff().getUser() == null) {
            return;
        }
        var user = src.getStaff().getUser();
        String name = ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
            + (user.getLastName() == null ? "" : user.getLastName())).trim();
        if (!name.isEmpty()) {
            appt.addParticipant()
                .setActor(new Reference().setDisplay(name))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        }
    }

    /**
     * The mapping choices worth defending: RESCHEDULED rows here are the
     * superseded booking (a new row carries the new time), so they read as
     * {@code cancelled}. IN_PROGRESS and FAILED both mean the visit BEGAN —
     * the HMS lifecycle reaches FAILED from IN_PROGRESS — and FHIR's
     * fulfilled tracks the appointment slot being consumed, not the clinical
     * outcome, so both read {@code fulfilled} alongside COMPLETED. UNKNOWN
     * maps to {@code pending}, the least-assertive open state, rather than
     * inventing certainty.
     */
    private static Appointment.AppointmentStatus mapStatus(AppointmentStatus status) {
        if (status == null) return Appointment.AppointmentStatus.PENDING;
        return switch (status) {
            case SCHEDULED, CONFIRMED -> Appointment.AppointmentStatus.BOOKED;
            case PENDING, UNKNOWN -> Appointment.AppointmentStatus.PENDING;
            case CHECKED_IN -> Appointment.AppointmentStatus.CHECKEDIN;
            case COMPLETED, IN_PROGRESS, FAILED -> Appointment.AppointmentStatus.FULFILLED;
            case CANCELLED, RESCHEDULED -> Appointment.AppointmentStatus.CANCELLED;
            case NO_SHOW -> Appointment.AppointmentStatus.NOSHOW;
        };
    }

    private static Date toDate(LocalDate date, LocalTime time) {
        return Date.from(date.atTime(time).atZone(ZoneId.systemDefault()).toInstant());
    }
}
