package com.example.hms.controller;

import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalReminderRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalRescheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO;
import com.example.hms.service.PrenatalSchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/prenatal")
@RequiredArgsConstructor
@Tag(name = "Prenatal Scheduling")
public class PrenatalSchedulingController {

    private final PrenatalSchedulingService prenatalSchedulingService;

    @PostMapping("/schedule")
    @PreAuthorize("hasAuthority('SCHEDULE_PRENATAL_APPOINTMENTS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')")
    @Operation(summary = "Generate prenatal appointment plan", description = "Calculates recommended prenatal visit cadence and maps existing appointments.")
    public ResponseEntity<PrenatalScheduleResponseDTO> generateSchedule(
        @Valid @RequestBody PrenatalScheduleRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);
        PrenatalScheduleResponseDTO response = prenatalSchedulingService.generateSchedule(request, locale, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/appointments/reschedule")
    @PreAuthorize("hasAuthority('SCHEDULE_PRENATAL_APPOINTMENTS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')")
    @Operation(summary = "Reschedule prenatal appointment", description = "Adjusts a prenatal appointment's time and staff assignment while preserving prenatal metadata.")
    public ResponseEntity<AppointmentResponseDTO> reschedulePrenatalAppointment(
        @Valid @RequestBody PrenatalRescheduleRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);
        AppointmentResponseDTO response = prenatalSchedulingService.reschedulePrenatalAppointment(request, locale, authentication.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reminders")
    @PreAuthorize("hasAuthority('SCHEDULE_PRENATAL_APPOINTMENTS') or hasAnyRole('SUPER_ADMIN','HOSPITAL_ADMIN','DOCTOR','NURSE','MIDWIFE','RECEPTIONIST')")
    @Operation(summary = "Create prenatal reminder", description = "Schedules a reminder notification for the patient's upcoming prenatal appointment.")
    public ResponseEntity<Void> createReminder(
        @Valid @RequestBody PrenatalReminderRequestDTO request,
        @RequestHeader(name = "Accept-Language", required = false) String lang,
        Authentication authentication
    ) {
        Locale locale = parseLocale(lang);
        prenatalSchedulingService.createReminder(request, locale, authentication.getName());
        return ResponseEntity.accepted().build();
    }

    private Locale parseLocale(String header) {
        if (header == null || header.isBlank()) {
            return Locale.getDefault();
        }
        String first = header.split(",")[0].trim().replace('_', '-');
        if (first.isBlank()) {
            return Locale.getDefault();
        }
        try {
            Locale.Builder builder = new Locale.Builder();
            String[] parts = first.split("-");
            builder.setLanguage(parts[0]);
            if (parts.length > 1) {
                builder.setRegion(parts[1]);
            }
            if (parts.length > 2) {
                builder.setVariant(parts[2]);
            }
            return builder.build();
        } catch (RuntimeException ex) {
            return Locale.getDefault();
        }
    }
}
