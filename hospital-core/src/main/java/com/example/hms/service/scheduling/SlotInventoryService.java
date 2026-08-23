package com.example.hms.service.scheduling;

import com.example.hms.payload.dto.scheduling.AppointmentSlotDTO;
import com.example.hms.payload.dto.scheduling.SlotGenerationResultDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Slot inventory (P2 #11) — foundation pass.
 *
 * <p>Scheduling could already book an Appointment against an arbitrary
 * (staff, date, time). What nothing could do was answer "when is this clinician
 * next free for a 30-minute follow-up?", because nothing described what a clinic
 * session IS. That one missing question is what blocks patient self-scheduling,
 * waitlist auto-offer and utilisation reporting alike — they are three readings
 * of the same model.
 */
public interface SlotInventoryService {

    /**
     * Materialise slots from every active template over a date range.
     *
     * <p>Idempotent: re-running for an overlapping window creates nothing new,
     * which matters because the natural way to operate this is a rolling
     * horizon that always re-covers the days it already covered.
     */
    SlotGenerationResultDTO generate(LocalDate from, LocalDate to);

    /** The open-slot search this model exists to make possible. */
    List<AppointmentSlotDTO> searchOpen(UUID departmentId, UUID staffId, UUID visitTypeId,
                                        LocalDate from, LocalDate to, int limit);

    /**
     * Reserve a slot briefly while a booking completes.
     *
     * <p>Booking is not instantaneous — choosing a time, confirming details and
     * paying takes minutes — and without a hold two people can be offered the
     * same moment and only one can have it.
     */
    AppointmentSlotDTO hold(UUID slotId, int holdMinutes);

    /** Release a hold early, when the patient backs out rather than times out. */
    AppointmentSlotDTO release(UUID slotId);

    /** Take a slot out of circulation — leave, a meeting, an equipment outage. */
    AppointmentSlotDTO block(UUID slotId, String reason);

    /** Reclaim holds whose window has passed. Returns how many were freed. */
    int reclaimExpiredHolds();

    /**
     * Book a slot: create a normal Appointment and stamp the slot BOOKED
     * (P3 #22). The appointment owns the time from that moment — slots are
     * pure capacity inventory, and cancelling the appointment is what frees
     * the slot again.
     */
    AppointmentSlotDTO book(UUID slotId, UUID patientId, String reason);

    /**
     * Free the slot an appointment was booked from, if any. Called by the
     * appointment lifecycle on cancel/reschedule — deliberately not
     * tenant-scoped, because the caller has already authorised the
     * appointment change and the slot must follow it.
     *
     * @return how many slots were freed (0 or 1)
     */
    int releaseForAppointment(UUID appointmentId);
}
