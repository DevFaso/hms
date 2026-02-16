package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for applying order sets to an admission
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionOrderExecutionRequestDTO {

    @NotNull(message = "Order set IDs are required")
    private List<UUID> orderSetIds;

    /**
     * Staff member applying the order sets
     */
    @NotNull(message = "Applied by staff ID is required")
    private UUID appliedByStaffId;
}
