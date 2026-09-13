package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Restrict or lift the restriction on a patient's chart (E8 #54).")
public class ChartRestrictionRequestDTO {

    @Schema(description = "true to restrict the chart (every read then needs a break-the-glass session), false to lift.")
    private boolean restricted;

    @Size(max = 512)
    @Schema(description = "Why the chart is restricted (VIP, staff member, family of staff …). Kept on the row while restricted.")
    private String reason;
}
