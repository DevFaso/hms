package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.RecordAccessPosture;
import jakarta.validation.constraints.NotNull;

public record UpdatePostureRequestDTO(
    @NotNull RecordAccessPosture posture
) {
}
