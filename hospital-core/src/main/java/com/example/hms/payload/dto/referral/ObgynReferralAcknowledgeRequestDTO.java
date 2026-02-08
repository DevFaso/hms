package com.example.hms.payload.dto.referral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralAcknowledgeRequestDTO {
    @NotNull
    private UUID obgynUserId;

    @NotBlank
    private String planSummary;
}
