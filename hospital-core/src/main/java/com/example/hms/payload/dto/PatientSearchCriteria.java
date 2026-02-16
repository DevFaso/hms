package com.example.hms.payload.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.UUID;

/**
 * Aggregates optional filters used when searching for patients.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientSearchCriteria {

    private String mrn;
    private String name;
    private String dateOfBirth;
    private String phone;
    private String email;
    private UUID hospitalId;
    private Boolean active;
}
