package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.RhFactor;
import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.enums.TransfusionUrgency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransfusionRequestResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String patientMrn;
    private UUID hospitalId;
    private UUID encounterId;
    private BloodProductType productType;
    private Integer unitsRequested;
    private String indication;
    private TransfusionUrgency urgency;
    private TransfusionRequestStatus status;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private LocalDateTime requiredBy;
    private String cancelReason;
    private String notes;

    /** The type and screen this request was raised against; null on emergency release. */
    private UUID bloodGroupId;
    private AboGroup patientAboGroup;
    private RhFactor patientRhFactor;
    /** False when the screen has lapsed — the crossmatch path refuses on this. */
    private Boolean screenCurrent;

    private List<BloodUnitResponseDTO> units;
    private List<CrossmatchResponseDTO> crossmatches;
    private LocalDateTime createdAt;
}
