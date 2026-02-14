package com.example.hms.patient.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MedicalHistoryDto {
    private String allergies;
    private String conditions;
    private String medications;
    private String surgeries;
    private String notes;
}
