package com.example.hms.payload.dto.discharge;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * DTO for pending test results at time of discharge
 * Part of Story #14: Discharge Summary Assembly
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingTestResultDTO {

    @NotBlank(message = "Test type is required")
    @Size(max = 50)
    private String testType; // LAB, IMAGING, PATHOLOGY, CULTURE

    @NotBlank(message = "Test name is required")
    @Size(max = 255)
    private String testName;

    @Size(max = 64)
    private String testCode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate orderDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedResultDate;

    @Size(max = 255)
    private String orderingProvider;

    @Size(max = 255)
    private String followUpProvider;

    @Size(max = 1000)
    private String notificationInstructions;

    private UUID labOrderId;
    private UUID imagingOrderId;

    private Boolean isCritical;
    private Boolean patientNotifiedOfPending;
}
