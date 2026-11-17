package com.example.hms.payload.dto;

import com.example.hms.enums.StaffLeaveType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StaffLeaveRequestDTO(
    @NotNull UUID staffId,
    @NotNull UUID hospitalId,
    UUID departmentId,
    @NotNull @FutureOrPresent LocalDate startDate,
    @NotNull @FutureOrPresent LocalDate endDate,
    LocalTime startTime,
    LocalTime endTime,
    @NotNull StaffLeaveType leaveType,
    Boolean requiresCoverage,
    String reason
) {
    // A body that leaves this out or sends null gets the documented default
    // (QA D23): Jackson 3 refuses a null for a primitive record component.
    public StaffLeaveRequestDTO {
        if (requiresCoverage == null) {
            requiresCoverage = Boolean.FALSE;
        }
    }
}
