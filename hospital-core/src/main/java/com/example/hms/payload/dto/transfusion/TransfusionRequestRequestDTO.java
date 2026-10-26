package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.TransfusionUrgency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** A clinician asking for blood. The requester is the authenticated caller. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransfusionRequestRequestDTO {

    @NotNull
    private UUID patientId;

    private UUID encounterId;

    @NotNull
    private BloodProductType productType;

    /** QA D884: 500 units was accepted; one request covers at most 20 (a massive-transfusion round is far fewer). */
    @NotNull
    @Min(1)
    @Max(value = 20, message = "{transfusion.request.unitsMax}")
    private Integer unitsRequested;

    @NotBlank
    @Size(max = 500)
    private String indication;

    private TransfusionUrgency urgency;

    private LocalDateTime requiredBy;

    @Size(max = 1000)
    private String notes;
}
