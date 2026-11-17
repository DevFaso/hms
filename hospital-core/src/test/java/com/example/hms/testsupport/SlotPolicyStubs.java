package com.example.hms.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.example.hms.service.scheduling.AppointmentSlotPolicy;
import java.time.LocalTime;

/**
 * QA D1060: unit tests of a booking service stub the slot rules with the
 * length they fall back to when no session template covers the time (30
 * minutes), so a test that is not about the visit length keeps booking as it
 * did; the policy itself is tested in {@code AppointmentSlotPolicyTest}.
 */
public final class SlotPolicyStubs {

    private SlotPolicyStubs() {
    }

    /**
     * QA D1093: every time a test books for a patient is a published, free
     * slot of {@link AppointmentSlotPolicy#DEFAULT_VISIT_MINUTES}; the policy's
     * own refusals are tested in {@code AppointmentSlotPolicyTest}.
     */
    public static void publishedSlots(AppointmentSlotPolicy slotPolicy) {
        lenient().when(slotPolicy.requirePublishedFreeSlot(any(), any(), any(), any())).thenAnswer(inv -> {
            java.time.LocalDateTime start = inv.getArgument(1);
            if (start == null) {
                return null;
            }
            return com.example.hms.model.scheduling.AppointmentSlot.builder()
                .slotDate(start.toLocalDate())
                .startAt(start)
                .endAt(start.plusMinutes(AppointmentSlotPolicy.DEFAULT_VISIT_MINUTES))
                .build();
        });
    }

    public static void defaultLength(AppointmentSlotPolicy slotPolicy) {
        lenient().when(slotPolicy.endFor(any(), any(), any())).thenAnswer(inv -> {
            LocalTime start = inv.getArgument(2);
            return start == null ? null : start.plusMinutes(AppointmentSlotPolicy.DEFAULT_VISIT_MINUTES);
        });
    }
}
