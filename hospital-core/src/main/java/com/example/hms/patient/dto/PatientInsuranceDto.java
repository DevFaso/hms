package com.example.hms.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
public class PatientInsuranceDto {
    private UUID id;

    @NotBlank
    @Size(max = 180)
    private String providerName;

    @Size(max = 120)
    private String policyNumber;

    @Size(max = 120)
    private String memberId;

    @Size(max = 120)
    private String groupNumber;

    private LocalDate coverageStart;
    private LocalDate coverageEnd;
    private boolean primaryPlan;
}
