package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateCandidateDTO {

    private UUID patientId;
    private String fullName;
    private String mrn;
    private LocalDate dateOfBirth;
    private String phone;
    private String email;

    /** 0-100: how closely this record matches the search criteria. */
    private int confidenceScore;
}
