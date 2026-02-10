package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NurseMedicationAdministrationRequestDTO {

    @NotBlank(message = "Medication administration status is required.")
    private String status;

    private String note;
}
