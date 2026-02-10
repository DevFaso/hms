package com.example.hms.payload.dto;

import com.example.hms.enums.StaffShiftStatus;
import jakarta.validation.constraints.NotNull;

public record StaffShiftStatusUpdateDTO(
    @NotNull StaffShiftStatus status,
    String cancellationReason
) { }
