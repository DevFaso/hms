package com.example.hms.payload.dto;

import com.example.hms.enums.AppointmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentFilterDTO {

    private UUID patientId;
    private UUID staffId;
    private UUID hospitalId;
    private UUID departmentId;
    private UUID createdById;

    private Set<AppointmentStatus> statuses;

    private LocalDate fromDate;
    private LocalDate toDate;

    private LocalTime fromStartTime;
    private LocalTime toEndTime;

    private Boolean upcomingOnly;

    private String patientEmail;
    private String staffEmail;
    private String hospitalName;
    private String patientName;
    private String staffName;
    private String search;
}
