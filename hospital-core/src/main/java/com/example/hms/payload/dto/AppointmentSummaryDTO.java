package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentSummaryDTO {
    private UUID id;
    private AppointmentStatus status;
    private LocalDate appointmentDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private UUID patientId;
    private String patientName;
    private String patientEmail;
    private String patientPhone;
    private UUID staffId;
    private String staffName;
    private String staffEmail;
    private UUID departmentId;
    private String departmentName;
    private String departmentPhone;
    private String departmentEmail;
    private UUID hospitalId;
    private String hospitalName;
    private String hospitalAddress;
    private String hospitalPhone;
    private String hospitalEmail;
    private String notes;
}
