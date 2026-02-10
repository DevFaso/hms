package com.example.hms.service;

import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalReminderRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalRescheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO;

import java.util.Locale;

public interface PrenatalSchedulingService {

    PrenatalScheduleResponseDTO generateSchedule(PrenatalScheduleRequestDTO request, Locale locale, String username);

    AppointmentResponseDTO reschedulePrenatalAppointment(PrenatalRescheduleRequestDTO request, Locale locale, String username);

    void createReminder(PrenatalReminderRequestDTO request, Locale locale, String username);
}
