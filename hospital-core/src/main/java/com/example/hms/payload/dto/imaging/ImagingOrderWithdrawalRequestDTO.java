package com.example.hms.payload.dto.imaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** QA D4140: why whoever placed a pending imaging request withdraws it. The service requires the reason. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingOrderWithdrawalRequestDTO {

    @NotBlank(message = "{order.lifecycle.cancelReasonRequired}")
    @Size(max = 500)
    private String cancellationReason;
}
