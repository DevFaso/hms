package com.example.hms.payload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Adds or updates one organism on a culture report (P3 #19). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroIsolateRequestDTO {

    @NotBlank
    @Size(max = 200)
    private String organismName;

    @Size(max = 50)
    private String organismCode;

    @Min(1)
    private Integer isolateNumber;

    @Size(max = 50)
    private String growthQuantity;

    @Size(max = 500)
    private String notes;

    /** Mandatory when the culture report is already FINAL/CORRECTED. */
    @Size(max = 500)
    private String correctionReason;
}
