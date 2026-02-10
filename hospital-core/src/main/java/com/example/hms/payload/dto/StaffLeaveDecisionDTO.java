package com.example.hms.payload.dto;

import com.example.hms.enums.StaffLeaveStatus;
import jakarta.validation.constraints.NotNull;

public record StaffLeaveDecisionDTO(
    @NotNull StaffLeaveStatus status,
    String managerNote
) { }
