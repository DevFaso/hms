package com.example.hms.fhir;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.SlotStatus;
import com.example.hms.fhir.mapper.AppointmentFhirMapper;
import com.example.hms.fhir.mapper.SlotFhirMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.scheduling.AppointmentSlot;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Appointment + Slot mapping (Tier 2 item 43). */
class AppointmentSlotFhirMapperTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 10, 0);

    private final AppointmentFhirMapper appointmentMapper = new AppointmentFhirMapper();
    private final SlotFhirMapper slotMapper =
        new SlotFhirMapper(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private static com.example.hms.model.Appointment appointment(AppointmentStatus status) {
        com.example.hms.model.Appointment src = new com.example.hms.model.Appointment();
        src.setId(UUID.randomUUID());
        src.setStatus(status);
        src.setAppointmentDate(LocalDate.of(2026, 9, 10));
        src.setStartTime(LocalTime.of(9, 0));
        src.setEndTime(LocalTime.of(9, 30));
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        src.setPatient(patient);
        return src;
    }

    @Test
    @DisplayName("an appointment carries its window and the patient participant")
    void appointmentBasics() {
        com.example.hms.model.Appointment src = appointment(AppointmentStatus.SCHEDULED);
        src.setReason("Programme visit");

        Appointment appt = appointmentMapper.toFhir(src);

        assertThat(appt.getStatus()).isEqualTo(Appointment.AppointmentStatus.BOOKED);
        assertThat(appt.getStart()).isNotNull();
        assertThat(appt.getEnd()).isAfter(appt.getStart());
        assertThat(appt.getDescription()).isEqualTo("Programme visit");
        assertThat(appt.getParticipantFirstRep().getActor().getReference())
            .isEqualTo("Patient/" + src.getPatient().getId());
    }

    @Test
    @DisplayName("every lifecycle state maps, and the judgement calls hold")
    void appointmentStatusMapping() {
        // RESCHEDULED rows are the superseded booking; FAILED never happened.
        assertThat(appointmentMapper.toFhir(appointment(AppointmentStatus.RESCHEDULED)).getStatus())
            .isEqualTo(Appointment.AppointmentStatus.CANCELLED);
        assertThat(appointmentMapper.toFhir(appointment(AppointmentStatus.FAILED)).getStatus())
            .isEqualTo(Appointment.AppointmentStatus.ENTEREDINERROR);
        assertThat(appointmentMapper.toFhir(appointment(AppointmentStatus.NO_SHOW)).getStatus())
            .isEqualTo(Appointment.AppointmentStatus.NOSHOW);
        assertThat(appointmentMapper.toFhir(appointment(AppointmentStatus.CHECKED_IN)).getStatus())
            .isEqualTo(Appointment.AppointmentStatus.CHECKEDIN);
        assertThat(appointmentMapper.toFhir(appointment(AppointmentStatus.UNKNOWN)).getStatus())
            .isEqualTo(Appointment.AppointmentStatus.PENDING);
    }

    private static AppointmentSlot slot(SlotStatus status) {
        AppointmentSlot src = new AppointmentSlot();
        src.setId(UUID.randomUUID());
        src.setStatus(status);
        src.setStartAt(LocalDateTime.of(2026, 9, 10, 9, 0));
        src.setEndAt(LocalDateTime.of(2026, 9, 10, 9, 30));
        return src;
    }

    @Test
    @DisplayName("slot statuses map onto the FHIR free/busy axis")
    void slotStatusMapping() {
        assertThat(slotMapper.toFhir(slot(SlotStatus.OPEN)).getStatus())
            .isEqualTo(Slot.SlotStatus.FREE);
        assertThat(slotMapper.toFhir(slot(SlotStatus.BOOKED)).getStatus())
            .isEqualTo(Slot.SlotStatus.BUSY);
        assertThat(slotMapper.toFhir(slot(SlotStatus.BLOCKED)).getStatus())
            .isEqualTo(Slot.SlotStatus.BUSYUNAVAILABLE);
    }

    @Test
    @DisplayName("an expired hold reads FREE - an abandoned booking must not hide a slot")
    void expiredHoldIsFree() {
        AppointmentSlot held = slot(SlotStatus.HELD);
        held.setHeldUntil(NOW.minusMinutes(5));
        assertThat(slotMapper.toFhir(held).getStatus()).isEqualTo(Slot.SlotStatus.FREE);

        AppointmentSlot stillHeld = slot(SlotStatus.HELD);
        stillHeld.setHeldUntil(NOW.plusMinutes(5));
        assertThat(slotMapper.toFhir(stillHeld).getStatus())
            .isEqualTo(Slot.SlotStatus.BUSYTENTATIVE);
    }

    @Test
    @DisplayName("the required schedule reference always carries a display")
    void scheduleReferencePresent() {
        Slot mapped = slotMapper.toFhir(slot(SlotStatus.OPEN));
        assertThat(mapped.getSchedule().getDisplay()).isNotBlank();
    }
}
