-- ============================================================
-- V142: put stranded RESCHEDULED appointments back into play.
-- ============================================================
--
-- WHY THIS EXISTS
--
-- The reschedule modal saved the new date and time together with
-- status = 'RESCHEDULED'. Every consumer treats that status as
-- terminal:
--
--   * the appointment detail page had no RESCHEDULED branch at all,
--     so it drew an empty action bar — no Confirm, no Check In,
--     nothing;
--   * the reception queue's canCheckIn() required SCHEDULED or
--     CONFIRMED, so no check-in icon appeared;
--   * ReceptionServiceImpl.checkInPatient rejects anything else
--     outright, so even calling the API by hand failed;
--   * and the reschedule had already released the slot.
--
-- The result is an appointment with a correct date, a correct time,
-- a real patient expected to walk through the door, and no action
-- available anywhere in the product. The patient arrives and the
-- front desk cannot check them in.
--
-- The application fix lands reschedules on SCHEDULED (see
-- AppointmentDetailComponent.submitReschedule) and the transition
-- map already allowed RESCHEDULED -> SCHEDULED — nothing ever
-- performed it. This migration performs it for the rows already
-- written.
--
-- SCOPE: today and later, only.
--
-- Past-dated RESCHEDULED rows are left exactly as they are. Those
-- are history — a visit that was moved and is now behind us — and
-- rewriting them to SCHEDULED would invent a queue of appointments
-- that were never held and can never be. The bug only harms
-- appointments somebody is still expected to attend, so that is all
-- this touches.
--
-- Slots are NOT re-reserved here. The reschedule released the slot
-- (AppointmentServiceImpl, on the CANCELLED/RESCHEDULED branch) and
-- re-reserving from SQL would bypass every availability and
-- double-booking check the booking path applies. The appointment
-- keeps its own date and time, which is what the front desk works
-- from; the slot inventory stays free, which is honest about what is
-- actually bookable. Reception can check these patients in either
-- way.
--
-- Idempotent by construction: the WHERE clause stops matching once
-- the rows are moved.
-- ============================================================

UPDATE clinical.appointments
   SET status     = 'SCHEDULED',
       updated_at = NOW()
 WHERE status = 'RESCHEDULED'
   AND appointment_date >= CURRENT_DATE;
