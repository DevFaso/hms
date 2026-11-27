package com.example.hms.payload.dto.imaging;

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

    @Size(max = 500)
    private String cancellationReason;
}
