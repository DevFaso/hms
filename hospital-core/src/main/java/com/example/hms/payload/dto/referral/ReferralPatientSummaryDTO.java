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
    /** QA D1036: the middle name, so the referral shows the patient's whole name. */
    private String middleName;
    private String lastName;
    private LocalDate dateOfBirth;
}
