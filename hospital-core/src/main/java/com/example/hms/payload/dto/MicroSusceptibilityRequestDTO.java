package com.example.hms.payload.dto;

import com.example.hms.enums.MicroSusceptibilityInterpretation;
import com.example.hms.enums.MicroSusceptibilityMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Adds one antibiotic susceptibility row to an isolate (P3 #19). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroSusceptibilityRequestDTO {

    @NotBlank
    @Size(max = 150)
    private String antibioticName;

    @Size(max = 50)
    private String antibioticCode;

    private MicroSusceptibilityMethod method;

    @Size(max = 30)
    private String micValue;

    @NotNull
    private MicroSusceptibilityInterpretation interpretation;

    @Size(max = 300)
    private String notes;

    /** Mandatory when the culture report is already FINAL/CORRECTED. */
    @Size(max = 500)
    private String correctionReason;
}
