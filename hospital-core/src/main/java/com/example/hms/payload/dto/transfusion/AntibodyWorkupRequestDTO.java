package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntibodyWorkupStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * What the biologist records about a patient's red-cell antibody: {@code IDENTIFIED} with its specificity, or
 * {@code REFERRED} with the centre, the reason and the request served before the answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntibodyWorkupRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private AntibodyWorkupStatus status;

    @Size(max = 500)
    private String antibodySpecificities;

    @Size(max = 200)
    private String referralCentre;

    @Size(max = 1000)
    private String decisionReason;

    private UUID transfusionRequestId;

    @Size(max = 1000)
    private String notes;
}
