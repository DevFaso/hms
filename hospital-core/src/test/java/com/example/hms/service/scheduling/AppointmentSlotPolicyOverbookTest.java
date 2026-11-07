package com.example.hms.service.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hms.enums.SlotStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** QA D1429: the desk may overbook, with a reason; a published free slot asks for none. */
class AppointmentSlotPolicyOverbookTest {

    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 19, 9, 0);

    private final AppointmentSlotRepository slots = mock(AppointmentSlotRepository.class);
    private final AppointmentSlotPolicy policy = new AppointmentSlotPolicy(slots,
        mock(SessionTemplateRepository.class),
        Clock.fixed(Instant.parse("2026-11-06T08:00:00Z"), ZoneOffset.UTC));

    private void slotAtStart(SlotStatus status, UUID hospitalId) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        AppointmentSlot slot = new AppointmentSlot();
        slot.setStatus(status);
        slot.setHospital(hospital);
        when(slots.findFirstByStaff_IdAndStartAt(STAFF, START)).thenReturn(Optional.of(slot));
    }

    @Test
    @DisplayName("a published free slot needs no reason, and a reason sent for one is not kept")
    void aFreeSlotNeedsNoReason() {
        slotAtStart(SlotStatus.OPEN, HOSPITAL);
        assertThat(policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, null)).isNull();
        assertThat(policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, "urgent")).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("no slot at that minute (a night, a closed day): refused on the field without a reason")
    void offTheSlotsNeedsAReason(String reason) {
        when(slots.findFirstByStaff_IdAndStartAt(STAFF, START)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, reason))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refused -> assertThat(refused.getField()).isEqualTo("overbookReason"));
    }

    @Test
    @DisplayName("off the slots, the reason is kept, trimmed")
    void theReasonIsKept() {
        when(slots.findFirstByStaff_IdAndStartAt(STAFF, START)).thenReturn(Optional.empty());
        assertThat(policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, "  urgent  ")).isEqualTo("urgent");
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"BOOKED", "BLOCKED"})
    @DisplayName("a slot that is not free is off the slots")
    void aTakenSlotIsNotFree(SlotStatus status) {
        slotAtStart(status, HOSPITAL);
        assertThatThrownBy(() -> policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, null))
            .isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("another hospital's slot is off the slots")
    void anotherHospitalsSlotIsNotFree() {
        slotAtStart(SlotStatus.OPEN, UUID.randomUUID());
        assertThatThrownBy(() -> policy.overbookReasonOffSlot(STAFF, START, HOSPITAL, null))
            .isInstanceOf(FieldBusinessException.class);
    }
}
