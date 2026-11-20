package com.example.hms.payload.dto.pharmacy;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** QA D2676 (r65): why a pharmacy refund is rejected or withdrawn. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PharmacyRefundReasonRequestDTO {

    @NotBlank
    @Size(max = 500)
    @NoCardNumber
    private String reason;
}
