package com.example.hms.payload.dto.clinical.labor;

import com.example.hms.enums.BabyOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * QA D1714: correct one baby's recorded outcome (stillborn to live, or live to stillborn). The reason is kept on the
 * fetal-death event; nothing is deleted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BabyOutcomeCorrectionRequestDTO {

    private UUID hospitalId;

    @NotNull
    private BabyOutcome outcome;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
