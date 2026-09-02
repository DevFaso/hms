package com.example.hms.fhir.mapper;

import com.example.hms.enums.SlotStatus;
import com.example.hms.model.scheduling.AppointmentSlot;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Slot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Slot inventory → FHIR R4 {@code Slot} (Tier 2 item 43). Newly
 * populatable: V121/V128 gave slots a real inventory and a booking writer,
 * which is why this was correctly absent before and is cheap now.
 *
 * <p>The {@code schedule} reference (required 1..1 in R4) is display-only —
 * staff and visit type — because no Schedule resource exists here yet. A
 * display-bearing reference is conformant; resolving it becomes possible
 * the day a Schedule provider earns its place.
 */
@Component
public class SlotFhirMapper {

    private final Clock clock;

    public SlotFhirMapper(Clock clock) {
        this.clock = clock;
    }

    public Slot toFhir(AppointmentSlot src) {
        if (src == null || src.getId() == null) return null;
        Slot slot = new Slot();
        slot.setId(src.getId().toString());
        slot.setStatus(mapStatus(src));
        slot.setStart(toDate(src.getStartAt()));
        slot.setEnd(toDate(src.getEndAt()));
        if (src.getVisitType() != null && src.getVisitType().getName() != null) {
            slot.addServiceType(new CodeableConcept().setText(src.getVisitType().getName()));
        }
        slot.setSchedule(new Reference().setDisplay(scheduleDisplay(src)));
        return slot;
    }

    /**
     * A HELD slot whose hold expired is FREE, not busy-tentative — the same
     * rule the open-slot search applies: a patient who abandoned a booking
     * must not keep the slot out of circulation until the reclaim sweep runs.
     */
    private Slot.SlotStatus mapStatus(AppointmentSlot src) {
        SlotStatus status = src.getStatus();
        if (status == null) return Slot.SlotStatus.BUSYUNAVAILABLE;
        return switch (status) {
            case OPEN -> Slot.SlotStatus.FREE;
            case HELD -> holdExpired(src) ? Slot.SlotStatus.FREE : Slot.SlotStatus.BUSYTENTATIVE;
            case BOOKED -> Slot.SlotStatus.BUSY;
            case BLOCKED -> Slot.SlotStatus.BUSYUNAVAILABLE;
        };
    }

    private boolean holdExpired(AppointmentSlot src) {
        return src.getHeldUntil() != null && src.getHeldUntil().isBefore(LocalDateTime.now(clock));
    }

    private static String scheduleDisplay(AppointmentSlot src) {
        StringBuilder display = new StringBuilder();
        if (src.getStaff() != null && src.getStaff().getUser() != null) {
            var user = src.getStaff().getUser();
            display.append(((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim());
        }
        if (src.getDepartment() != null && src.getDepartment().getName() != null) {
            if (!display.isEmpty()) display.append(" — ");
            display.append(src.getDepartment().getName());
        }
        return display.isEmpty() ? "Schedule" : display.toString();
    }

    private static Date toDate(LocalDateTime value) {
        if (value == null) return null;
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
}
