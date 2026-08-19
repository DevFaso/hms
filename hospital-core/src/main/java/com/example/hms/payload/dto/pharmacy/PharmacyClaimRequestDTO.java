package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyClaimRequestDTO {

    @NotNull(message = "Dispense ID is required")
    private UUID dispenseId;

    @NotNull(message = "Patient ID is required")
    private UUID patientId;

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @Size(max = 255)
    private String coverageReference;

    private PharmacyClaimStatus claimStatus;

    @NotNull(message = "Claim amount is required")
    private BigDecimal amount;

    @Size(max = 10)
    private String currency;

    @Size(max = 1000)
    private String rejectionReason;

    @Size(max = 1000)
    private String notes;
}
