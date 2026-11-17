package com.example.hms.payload.dto.mortality;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An administrator's "death upheld": the reason is required. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeathDisputeOutcomeDTO {

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
