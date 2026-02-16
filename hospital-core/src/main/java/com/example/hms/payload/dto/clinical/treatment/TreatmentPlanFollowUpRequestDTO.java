package com.example.hms.payload.dto.clinical.treatment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreatmentPlanFollowUpRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String label;

    @Size(max = 4000)
    private String instructions;

    private LocalDate dueOn;

    private UUID assignedStaffId;
}
