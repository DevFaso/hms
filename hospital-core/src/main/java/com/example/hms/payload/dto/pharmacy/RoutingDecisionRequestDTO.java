package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.RoutingType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingDecisionRequestDTO {

    @NotNull(message = "Prescription ID is required")
    private UUID prescriptionId;

    @NotNull(message = "Routing type is required")
    private RoutingType routingType;

    private UUID targetPharmacyId;

    @Size(max = 1024, message = "Reason must not exceed 1024 characters")
    private String reason;

    private LocalDate estimatedRestockDate;
}
