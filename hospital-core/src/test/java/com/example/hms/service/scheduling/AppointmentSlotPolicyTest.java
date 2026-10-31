package com.example.hms.service.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.model.scheduling.SessionTemplate;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** QA D1060 / D1063: the slot rules set the visit length and keep a held slot for its offer. */
class AppointmentSlotPolicyTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);

    private final AppointmentSlotRepository slotRepository = mock(AppointmentSlotRepository.class);
    private final SessionTemplateRepository templateRepository = mock(SessionTemplateRepository.class);
    private final UUID staffId = UUID.randomUUID();
    private AppointmentSlotPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AppointmentSlotPolicy(slotRepository, templateRepository, CLOCK);
        when(slotRepository.findFirstByStaff_IdAndStartAt(any(), any())).thenReturn(Optional.empty());
        when(templateRepository.findByStaff_IdAndActiveTrue(any())).thenReturn(List.of());
        when(slotRepository.findByStaff_IdAndSlotDate(any(), any())).thenReturn(List.of());
    }

    @Test
    void aGeneratedSlotGivesTheEnd() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(8, 20));
        when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start))
            .thenReturn(Optional.of(slot(start, start.plusMinutes(20), SlotStatus.OPEN, null)));

        // The portal used to book 08:20-08:50 against 20-minute slots.
        assertThat(policy.endFor(staffId, FRIDAY, LocalTime.of(8, 20))).isEqualTo(LocalTime.of(8, 40));
    }

    @Test
    void theSessionTemplateGivesTheLengthWhenNoSlotWasGenerated() {
        SessionTemplate template = SessionTemplate.builder()
            .dayOfWeek((short) FRIDAY.getDayOfWeek().getValue())
            .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(12, 0))
            .slotMinutes(20).effectiveFrom(FRIDAY.minusMonths(1)).active(true)
            .build();
        when(templateRepository.findByStaff_IdAndActiveTrue(staffId)).thenReturn(List.of(template));

        assertThat(policy.endFor(staffId, FRIDAY, LocalTime.of(10, 0))).isEqualTo(LocalTime.of(10, 20));
        // Outside the session: the default length.
        assertThat(policy.endFor(staffId, FRIDAY, LocalTime.of(14, 0))).isEqualTo(LocalTime.of(14, 30));
    }

    @Test
    void withNoRuleTheDefaultLengthNeverAWholeDay() {
        assertThat(policy.endFor(staffId, FRIDAY, LocalTime.of(7, 0))).isEqualTo(LocalTime.of(7, 30));
        assertThat(policy.endFor(null, FRIDAY, LocalTime.of(9, 0))).isEqualTo(LocalTime.of(9, 30));
        // Never past midnight.
        assertThat(policy.endFor(staffId, FRIDAY, LocalTime.of(23, 50))).isEqualTo(LocalTime.of(23, 59));
        // ... and a visit that would last no time at all is refused.
        LocalTime lastMinute = LocalTime.of(23, 59);
        assertThatThrownBy(() -> policy.endFor(staffId, FRIDAY, lastMinute))
            .isInstanceOf(BusinessException.class).hasMessageContaining("appointment.timeRange.invalid");
    }

    @Test
    void aLiveHoldRefusesAnOverlappingBooking() {
        LocalDateTime held = LocalDateTime.of(FRIDAY, LocalTime.of(8, 0));
        when(slotRepository.findByStaff_IdAndSlotDate(staffId, FRIDAY)).thenReturn(List.of(
            slot(held, held.plusMinutes(20), SlotStatus.HELD, LocalDateTime.now(CLOCK).plusHours(2))));

        LocalDateTime heldEnd = held.plusMinutes(20);
        assertThatThrownBy(() -> policy.requireNoLiveHold(staffId, held, heldEnd))
            .isInstanceOf(BusinessException.class).hasMessageContaining("slot.heldForOffer");
        LocalDateTime overlapStart = held.plusMinutes(10);
        LocalDateTime overlapEnd = held.plusMinutes(30);
        assertThatThrownBy(() -> policy.requireNoLiveHold(staffId, overlapStart, overlapEnd))
            .isInstanceOf(BusinessException.class);
        // Next to it, not over it.
        assertThatCode(() -> policy.requireNoLiveHold(staffId, held.plusMinutes(20), held.plusMinutes(40)))
            .doesNotThrowAnyException();
    }

    @Test
    void anExpiredHoldOrAnOpenSlotDoesNotRefuse() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(9, 0));
        when(slotRepository.findByStaff_IdAndSlotDate(staffId, FRIDAY)).thenReturn(List.of(
            slot(start, start.plusMinutes(20), SlotStatus.HELD, LocalDateTime.now(CLOCK).minusMinutes(1)),
            slot(start, start.plusMinutes(20), SlotStatus.OPEN, null)));

        assertThatCode(() -> policy.requireNoLiveHold(staffId, start, start.plusMinutes(20)))
            .doesNotThrowAnyException();
    }

    // ── QA D1093: a patient's own booking takes exactly one published, free slot ──

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID departmentId = UUID.randomUUID();

    private AppointmentSlot publishedSlot(LocalDateTime start, SlotStatus status, LocalDateTime heldUntil) {
        AppointmentSlot s = slot(start, start.plusMinutes(20), status, heldUntil);
        com.example.hms.model.Hospital h = new com.example.hms.model.Hospital();
        h.setId(hospitalId);
        com.example.hms.model.Department d = new com.example.hms.model.Department();
        d.setId(departmentId);
        s.setHospital(h);
        s.setDepartment(d);
        return s;
    }

    @Test
    void d1093_aMinuteBetweenTwoSlotsIsRefused() {
        // QA D1093 step 1: 10:10 against 20-minute slots at 10:00 and 10:20.
        LocalDateTime offGrid = LocalDateTime.of(FRIDAY, LocalTime.of(10, 10));
        assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, offGrid, hospitalId, departmentId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
    }

    @Test
    void d1093_aDayOrAnHourWithNoSessionIsRefused() {
        // QA D1093 steps 3-4: 15:00 with no afternoon session, a Saturday with no template.
        for (LocalDateTime t : List.of(LocalDateTime.of(FRIDAY, LocalTime.of(15, 0)),
                LocalDateTime.of(FRIDAY.plusDays(1), LocalTime.of(9, 0)))) {
            assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, t, hospitalId, departmentId))
                .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
        }
    }

    @Test
    void d1093_aBookedOrBlockedSlotIsNotFree() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(10, 20));
        for (SlotStatus taken : List.of(SlotStatus.BOOKED, SlotStatus.BLOCKED)) {
            when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start))
                .thenReturn(Optional.of(publishedSlot(start, taken, null)));
            assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, start, hospitalId, departmentId))
                .hasFieldOrPropertyWithValue("messageKey", "portal.booking.slotNotFree");
        }
        when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start))
            .thenReturn(Optional.of(publishedSlot(start, SlotStatus.HELD, LocalDateTime.now(CLOCK).plusMinutes(5))));
        assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, start, hospitalId, departmentId))
            .hasFieldOrPropertyWithValue("messageKey", "portal.booking.slotNotFree");
    }

    @Test
    void d1093_anOpenSlotOrARunOutHoldIsTaken() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(10, 20));
        AppointmentSlot open = publishedSlot(start, SlotStatus.OPEN, null);
        when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start)).thenReturn(Optional.of(open));
        assertThat(policy.requirePublishedFreeSlot(staffId, start, hospitalId, departmentId)).isSameAs(open);
        assertThat(policy.requirePublishedFreeSlot(staffId, start, hospitalId, null)).isSameAs(open);

        AppointmentSlot lapsed = publishedSlot(start, SlotStatus.HELD, LocalDateTime.now(CLOCK).minusMinutes(1));
        when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start)).thenReturn(Optional.of(lapsed));
        assertThat(policy.requirePublishedFreeSlot(staffId, start, hospitalId, departmentId)).isSameAs(lapsed);
    }

    @Test
    void d1093_aSlotOfAnotherClinicIsNoPublishedSlot() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(10, 20));
        when(slotRepository.findFirstByStaff_IdAndStartAt(staffId, start))
            .thenReturn(Optional.of(publishedSlot(start, SlotStatus.OPEN, null)));
        assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, start, UUID.randomUUID(), departmentId))
            .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
        assertThatThrownBy(() -> policy.requirePublishedFreeSlot(staffId, start, hospitalId, UUID.randomUUID()))
            .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
        assertThatThrownBy(() -> policy.requirePublishedFreeSlot(null, start, hospitalId, departmentId))
            .hasFieldOrPropertyWithValue("messageKey", "portal.booking.notAPublishedSlot");
    }

    @Test
    void d1093_takingASlotBooksItForTheAppointment() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(10, 20));
        AppointmentSlot lapsed = publishedSlot(start, SlotStatus.HELD, LocalDateTime.now(CLOCK).minusMinutes(1));
        lapsed.setHeldByUserId(UUID.randomUUID());
        com.example.hms.model.Appointment appointment = new com.example.hms.model.Appointment();

        policy.takeSlot(lapsed, appointment);

        assertThat(lapsed.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(lapsed.getAppointment()).isSameAs(appointment);
        assertThat(lapsed.getHeldUntil()).isNull();
        assertThat(lapsed.getHeldByUserId()).isNull();
        org.mockito.Mockito.verify(slotRepository).saveAndFlush(lapsed);
    }

    @Test
    void d1093_aConcurrentTakerIsAFriendlyRefusal() {
        AppointmentSlot open = publishedSlot(LocalDateTime.of(FRIDAY, LocalTime.of(10, 20)), SlotStatus.OPEN, null);
        when(slotRepository.saveAndFlush(open))
            .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("raced"));
        assertThatThrownBy(() -> policy.takeSlot(open, new com.example.hms.model.Appointment()))
            .hasFieldOrPropertyWithValue("messageKey", "slot.justTaken");
    }

    @Test
    void d1093_anyClinicianMeansOnlyOfferableSlotsAtThatTime() {
        LocalDateTime start = LocalDateTime.of(FRIDAY, LocalTime.of(10, 20));
        AppointmentSlot open = publishedSlot(start, SlotStatus.OPEN, null);
        open.setStaff(new com.example.hms.model.Staff());
        AppointmentSlot booked = publishedSlot(start, SlotStatus.BOOKED, null);
        booked.setStaff(new com.example.hms.model.Staff());
        when(slotRepository.findByDepartment_IdAndStartAt(departmentId, start)).thenReturn(List.of(open, booked));
        assertThat(policy.offerableAt(departmentId, start)).containsExactly(open);
        assertThat(policy.offerableAt(null, start)).isEmpty();
    }

    private static AppointmentSlot slot(LocalDateTime start, LocalDateTime end, SlotStatus status,
                                        LocalDateTime heldUntil) {
        return AppointmentSlot.builder().slotDate(start.toLocalDate()).startAt(start).endAt(end)
            .status(status).heldUntil(heldUntil).build();
    }
}
