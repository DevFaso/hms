package com.example.hms.service.scheduling;

import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.service.AppointmentSlotRules;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The clinic's slot rules, read by every path that gives a clinician's time
 * to a patient: the desk booking and edit, the patient's own booking and
 * reschedule, the referral appointment and the check-out follow-up. (The slot
 * inventory's own {@code book} takes the slot row itself, whose end this
 * class would return anyway.)
 *
 * <p>QA D1060: the slot rules decide how long a visit lasts, never the person
 * booking it. A patient could send any end time: the portal booked 30 minutes
 * against 20-minute slots, and an API call took a clinician's whole day
 * (07:00-19:00) for one self-booking. QA D1063: a slot held for a waitlist
 * offer is not given to anyone else while the hold lasts; the desk booked over
 * it, and the offered patient's accept then failed.
 *
 * <p>{@code AppointmentSlotRulesGuardTest} fails the build when a booking path
 * sets an end time or books a clinician without going through here.
 */
@Component
@RequiredArgsConstructor
public class AppointmentSlotPolicy {

    /**
     * The visit length when no session template covers the time (an ad-hoc
     * clinic): the length every booking path already defaulted to.
     */
    public static final int DEFAULT_VISIT_MINUTES = 30;

    private final AppointmentSlotRepository slotRepository;
    private final SessionTemplateRepository templateRepository;
    private final Clock clock;

    /**
     * When a visit with {@code staffId} starting at {@code start} on {@code date}
     * ends, by the slot rules: the generated slot that starts then, else the
     * active session template that covers that time (its slot length), else
     * {@link #DEFAULT_VISIT_MINUTES}. Never past midnight: a visit that would
     * run into the next day ends at 23:59.
     */
    public LocalTime endFor(UUID staffId, LocalDate date, LocalTime start) {
        if (start == null) {
            throw new BusinessException("appointment.timeRange.invalid");
        }
        int minutes = minutesFor(staffId, date, start);
        LocalTime end = start.plusMinutes(minutes);
        return end.isAfter(start) ? end : LocalTime.of(23, 59);
    }

    private int minutesFor(UUID staffId, LocalDate date, LocalTime start) {
        if (staffId == null || date == null) {
            return DEFAULT_VISIT_MINUTES;
        }
        LocalDateTime startAt = LocalDateTime.of(date, start);
        AppointmentSlot slot = slotRepository.findFirstByStaff_IdAndStartAt(staffId, startAt).orElse(null);
        if (slot != null && slot.getEndAt() != null && slot.getEndAt().isAfter(startAt)) {
            return (int) java.time.Duration.between(startAt, slot.getEndAt()).toMinutes();
        }
        return templateRepository.findByStaff_IdAndActiveTrue(staffId).stream()
            .filter(t -> t.appliesOn(date) && covers(t, start))
            .min(Comparator.comparing(SessionTemplate::getStartTime))
            .map(SessionTemplate::getSlotMinutes)
            .filter(m -> m > 0)
            .orElse(DEFAULT_VISIT_MINUTES);
    }

    private static boolean covers(SessionTemplate template, LocalTime start) {
        return template.getStartTime() != null && template.getEndTime() != null
            && start.compareTo(template.getStartTime()) >= 0 && start.compareTo(template.getEndTime()) < 0;
    }

    /**
     * QA D1063: refuses {@code [start, end)} when it overlaps a slot of the
     * clinician that is HELD by a live hold (a waitlist offer waiting for the
     * patient's answer). The slot inventory refused to book such a slot; the
     * other booking paths ignored the hold.
     */
    public void requireNoLiveHold(UUID staffId, LocalDateTime start, LocalDateTime end) {
        if (staffId == null || start == null || end == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean held = slotRepository.findByStaff_IdAndSlotDate(staffId, start.toLocalDate()).stream()
            .filter(s -> s.getStatus() == SlotStatus.HELD)
            .filter(s -> s.getHeldUntil() != null && s.getHeldUntil().isAfter(now))
            .anyMatch(s -> AppointmentSlotRules.overlaps(start, end, s.getStartAt(), s.getEndAt()));
        if (held) {
            throw new BusinessException("slot.heldForOffer");
        }
    }
}
