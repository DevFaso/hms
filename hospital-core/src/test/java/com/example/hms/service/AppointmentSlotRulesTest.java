package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Appointment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentSlotRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    private static Appointment booked(AppointmentStatus status, int hour) {
        Appointment a = new Appointment();
        a.setId(UUID.randomUUID());
        a.setAppointmentDate(DAY);
        a.setStartTime(LocalTime.of(hour, 0));
        a.setEndTime(LocalTime.of(hour, 30));
        a.setStatus(status);
        return a;
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "NO_SHOW"})
    @DisplayName("D937: a cancelled booking or a no-show frees its slot")
    void freedSlots(AppointmentStatus status) {
        assertThat(AppointmentSlotRules.overlapsHeldSlot(List.of(booked(status, 14)), null,
            LocalDateTime.of(DAY, LocalTime.of(14, 0)), LocalDateTime.of(DAY, LocalTime.of(14, 30)))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "NO_SHOW"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other status keeps its slot")
    void heldSlots(AppointmentStatus status) {
        assertThat(AppointmentSlotRules.overlapsHeldSlot(List.of(booked(status, 14)), null,
            LocalDateTime.of(DAY, LocalTime.of(14, 15)), LocalDateTime.of(DAY, LocalTime.of(14, 45)))).isTrue();
    }

    @Test
    @DisplayName("the appointment being moved never conflicts with itself")
    void selfIsExcluded() {
        Appointment self = booked(AppointmentStatus.SCHEDULED, 9);
        assertThat(AppointmentSlotRules.overlapsHeldSlot(List.of(self), self.getId(),
            LocalDateTime.of(DAY, LocalTime.of(9, 0)), LocalDateTime.of(DAY, LocalTime.of(9, 30)))).isFalse();
    }

    @Test
    @DisplayName("D978: a patient books a future time inside the patient booking day")
    void patientBookingTimes() {
        LocalDateTime now = LocalDateTime.of(DAY, LocalTime.of(22, 51));
        assertThatThrownBy(() -> AppointmentSlotRules.requirePatientBookable(
            LocalDateTime.of(DAY, LocalTime.of(8, 0)), LocalDateTime.of(DAY, LocalTime.of(8, 30)), now))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("portal.booking.pastTime");
        assertThatThrownBy(() -> AppointmentSlotRules.requirePatientBookable(
            LocalDateTime.of(DAY.plusDays(6), LocalTime.of(2, 30)),
            LocalDateTime.of(DAY.plusDays(6), LocalTime.of(3, 0)), now))
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("portal.booking.outsideHours");
        AppointmentSlotRules.requirePatientBookable(
            LocalDateTime.of(DAY.plusDays(1), LocalTime.of(9, 0)),
            LocalDateTime.of(DAY.plusDays(1), LocalTime.of(9, 30)), now);
    }
}
