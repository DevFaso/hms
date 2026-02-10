package com.example.hms.payload.dto.referral;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObgynReferralCancelRequestDTO {
    @NotBlank
    private String reason;
}
