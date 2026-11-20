package com.example.hms.payload.dto.pharmacy;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Policy rule 6 (V345): why a deferral is rejected or withdrawn, or the note of the review of an override. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DispenseBillingDeferralReasonDTO {

    @NotBlank
    @Size(max = 500)
    @NoCardNumber
    private String reason;
}
