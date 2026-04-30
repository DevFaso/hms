package com.example.hms.payload.dto.appointment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight calendar-grid view of an appointment. Shape matches the
 * FullCalendar event contract on the frontend so the picker renders
 * without a second-pass mapping.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppointmentCalendarEventDTO(
    UUID id,
    UUID patientId,
    String patientName,
    UUID resourceId,
    String resourceName,
    String title,
    LocalDateTime start,
    LocalDateTime end,
    String status,
    String reason
) {}
