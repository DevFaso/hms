package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResultRequestDTO {

    private UUID id;

    @NotNull
    private UUID labOrderId;

    @NotNull
    private UUID assignmentId;

    @NotNull
    private UUID patientId;

    @NotBlank
    private String resultValue;

    private String resultUnit;

    @NotNull
    private LocalDateTime resultDate;

    private String notes;
}

