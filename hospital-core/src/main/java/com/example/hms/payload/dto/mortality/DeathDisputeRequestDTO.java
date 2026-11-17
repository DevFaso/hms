package com.example.hms.payload.dto.mortality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A doctor's dispute of a recorded death. Both statements must be true: the service refuses a false one on its field. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathDisputeRequestDTO {

    @NotBlank
    @Size(max = 1000)
    private String reason;

    /** The doctor has assessed the patient as alive. */
    @NotNull
    private Boolean assessedAlive;

    /** The doctor has verified the patient's identity: the death entry could belong to another person. */
    @NotNull
    private Boolean identityVerified;
}
