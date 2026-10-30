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
    }

    @Test
    void aLiveHoldRefusesAnOverlappingBooking() {
        LocalDateTime held = LocalDateTime.of(FRIDAY, LocalTime.of(8, 0));
        when(slotRepository.findByStaff_IdAndSlotDate(staffId, FRIDAY)).thenReturn(List.of(
            slot(held, held.plusMinutes(20), SlotStatus.HELD, LocalDateTime.now(CLOCK).plusHours(2))));

        assertThatThrownBy(() -> policy.requireNoLiveHold(staffId, held, held.plusMinutes(20)))
            .isInstanceOf(BusinessException.class).hasMessageContaining("slot.heldForOffer");
        assertThatThrownBy(() -> policy.requireNoLiveHold(staffId, held.plusMinutes(10), held.plusMinutes(30)))
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

    private static AppointmentSlot slot(LocalDateTime start, LocalDateTime end, SlotStatus status,
                                        LocalDateTime heldUntil) {
        return AppointmentSlot.builder().slotDate(start.toLocalDate()).startAt(start).endAt(end)
            .status(status).heldUntil(heldUntil).build();
    }
}
