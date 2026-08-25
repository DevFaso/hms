package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.RhFactor;
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
public class PatientBloodGroupResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private UUID hospitalId;
    private AboGroup aboGroup;
    private RhFactor rhFactor;
    private AntibodyScreenResult antibodyScreen;
    private String antibodyDetail;
    private LocalDateTime specimenCollectedAt;
    private LocalDateTime performedAt;
    private LocalDateTime expiresAt;
    private String performedByName;
    private Boolean superseded;
    /** Whether the SCREEN is still usable for a crossmatch. ABO/Rh never expires. */
    private Boolean screenCurrent;
    private String notes;
    private LocalDateTime createdAt;
}
