package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.RhFactor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A type and screen result. The performer is the authenticated caller and the
 * hospital comes from their active scope — neither is accepted from the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatientBloodGroupRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private AboGroup aboGroup;

    @NotNull
    private RhFactor rhFactor;

    @NotNull
    private AntibodyScreenResult antibodyScreen;

    @Size(max = 500)
    private String antibodyDetail;

    private LocalDateTime specimenCollectedAt;

    /** When this screen goes stale. Defaults to 72h from now when omitted. */
    private LocalDateTime expiresAt;

    /**
     * Required when the ABO/Rh differs from the standing group. A blood group
     * does not change, so a disagreement is either a lab error now or a lab
     * error then, and the service refuses to let one overwrite the other
     * silently.
     */
    @Size(max = 500)
    private String correctionReason;

    @Size(max = 1000)
    private String notes;
}
