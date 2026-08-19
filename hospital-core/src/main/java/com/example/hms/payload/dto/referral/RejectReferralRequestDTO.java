package com.example.hms.payload.dto.referral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectReferralRequestDTO {

    @NotBlank
    @Size(max = 500)
    private String reason;
}
