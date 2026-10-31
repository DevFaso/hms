package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Appointment;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The scheduling rules every booking path applies: the staff booking and edit
 * ({@link AppointmentServiceImpl}) and the patient's own booking and
 * reschedule ({@code PatientPortalServiceImpl}). One definition, so a rule
 * fixed on one path cannot stay broken on another (QA D937, D977, D978).
 * {@code AppointmentSlotRulesGuardTest} fails the build when a booking path
 * compares appointment times without going through here.
 */
public final class AppointmentSlotRules {

    /**
     * QA D978: the earliest start a patient may book for themselves. The desk
     * can still book any time (an on-call clinician, a night clinic); a patient
     * choosing their own slot at 02:30 is a mistake, not a booking.
     */
    public static final LocalTime PATIENT_DAY_START = LocalTime.of(7, 0);

    /** QA D978: the latest end a patient may book for themselves. */
    public static final LocalTime PATIENT_DAY_END = LocalTime.of(19, 0);

    private AppointmentSlotRules() {
    }

    /**
     * Whether {@code [start, end)} overlaps an appointment of
     * {@code sameDayBookings} that still holds its slot
     * ({@link AppointmentStatus#holdsSlot}). {@code excludeId}, when set, is
     * the appointment being moved, which never conflicts with itself.
     * QA D937: a cancelled booking or a no-show used to keep blocking the
     * clinician's time.
     */
    public static boolean overlapsHeldSlot(List<Appointment> sameDayBookings, UUID excludeId,
                                           LocalDateTime start, LocalDateTime end) {
        return sameDayBookings.stream()
            .filter(a -> excludeId == null || !excludeId.equals(a.getId()))
            .filter(a -> AppointmentStatus.holdsSlot(a.getStatus()))
            .filter(a -> a.getStartTime() != null)
            .anyMatch(a -> {
                // The rows are one day's bookings (callers query by date); a row
                // with no date is on that day, and one with no end still holds
                // its start minute.
                java.time.LocalDate day = a.getAppointmentDate() != null ? a.getAppointmentDate() : start.toLocalDate();
                LocalDateTime existingStart = LocalDateTime.of(day, a.getStartTime());
                LocalDateTime existingEnd = a.getEndTime() != null
                    ? LocalDateTime.of(day, a.getEndTime())
                    : existingStart.plusMinutes(1);
                return start.isBefore(existingEnd) && existingStart.isBefore(end);
            });
    }

    /**
     * Whether {@code [aStart, aEnd)} and {@code [bStart, bEnd)} share a moment:
     * the one interval test every booking rule uses (QA D1063: a booking over
     * a slot held for a waitlist offer).
     */
    public static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                                   LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * QA D978: a slot a patient books or moves to for themselves starts in the
     * future and lies within the patient booking day. Only a past DATE used to
     * be refused, so a time already gone today, or 02:30, was booked.
     */
    public static void requirePatientBookable(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (!start.isAfter(now)) {
            throw new BusinessException("portal.booking.pastTime");
        }
        if (start.toLocalTime().isBefore(PATIENT_DAY_START)
                || end.toLocalTime().isAfter(PATIENT_DAY_END)
                || !end.toLocalDate().equals(start.toLocalDate())) {
            throw new BusinessException("portal.booking.outsideHours",
                PATIENT_DAY_START.toString(), PATIENT_DAY_END.toString());
        }
    }
}
