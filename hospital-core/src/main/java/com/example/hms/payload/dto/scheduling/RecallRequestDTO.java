package com.example.hms.payload.dto.scheduling;

import com.example.hms.enums.RecallType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** Manually create a patient recall (P3 #22). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecallRequestDTO {

    @NotNull(message = "A patient is required.")
    private UUID patientId;

    private UUID departmentId;

    private UUID preferredProviderId;

    /** Defaults to FOLLOW_UP. */
    private RecallType recallType;

    @NotNull(message = "A due date is required.")
    private LocalDate dueDate;

    @NotBlank(message = "A reason is required.")
    @Size(max = 500)
    private String reason;

    @Size(max = 500)
    private String notes;
}
