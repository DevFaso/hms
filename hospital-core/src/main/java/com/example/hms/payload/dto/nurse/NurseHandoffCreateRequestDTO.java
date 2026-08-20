package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Payload for recording a new SBAR handoff from the nurse station. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseHandoffCreateRequestDTO {

    @NotNull
    private UUID patientId;

    /** e.g. "Shift change", "Transfer to Radiology". */
    @NotBlank
    @Size(max = 200)
    private String direction;

    @Size(max = 4000)
    private String situation;

    @Size(max = 4000)
    private String background;

    @Size(max = 4000)
    private String assessment;

    @Size(max = 4000)
    private String recommendation;
}
