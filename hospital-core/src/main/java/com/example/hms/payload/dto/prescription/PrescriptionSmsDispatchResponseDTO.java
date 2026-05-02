package com.example.hms.payload.dto.prescription;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of dispatching a prescription by SMS to a community pharmacy.")
public class PrescriptionSmsDispatchResponseDTO {

    private UUID prescriptionId;
    private UUID transmissionId;
    private UUID pharmacyId;
    private String pharmacyName;
    private String destinationPhone;
    private String status;
    private LocalDateTime dispatchedAt;
}
