package com.example.hms.payload.dto.referral;

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
public class ReferralPatientSummaryDTO {
    private UUID id;
    private String mrn;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
}
