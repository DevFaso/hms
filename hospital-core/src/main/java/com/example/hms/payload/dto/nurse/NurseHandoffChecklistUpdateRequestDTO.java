package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NurseHandoffChecklistUpdateRequestDTO {

    @NotNull
    private Boolean completed;
}
