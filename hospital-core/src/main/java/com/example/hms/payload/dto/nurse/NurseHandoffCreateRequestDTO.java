package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseHandoffCreateRequestDTO {

    @NotNull(message = "Patient ID is required for a handoff.")
    private UUID patientId;

    @NotBlank(message = "Handoff direction is required.")
    @Size(max = 255)
    private String direction;

    @Size(max = 4000)
    private String note;

    /** Optional checklist item descriptions to attach to the handoff. */
    @Builder.Default
    private List<String> checklistItems = new ArrayList<>();
}
