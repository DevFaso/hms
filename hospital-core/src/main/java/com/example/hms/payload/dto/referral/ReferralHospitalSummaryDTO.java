package com.example.hms.payload.dto.referral;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralHospitalSummaryDTO {
    private UUID id;
    private String name;
    private String code;
}
