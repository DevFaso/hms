package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class NurseMedicationAdministrationRequestDTO {

    @NotBlank(message = "Medication administration status is required.")
    private String status;

    private String note;

    /**
     * When the bedside five-rights check fails but the nurse proceeds anyway,
     * a non-blank reason is required (P1 #8). The eMAR service rejects an
     * administration that proceeds past a failed right without one.
     */
    @Size(max = 1024)
    private String overrideReason;
}
