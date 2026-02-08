package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentStatsDTO {
    private int totalStaff;
    private int totalDoctors;
    private int totalNurses;
    private int totalPatientsHandled;
    private int totalAppointments;
}

