package com.example.hms.payload.dto;

import com.example.hms.enums.BreakGlassReviewOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Compliance sign-off on a break-the-glass session (E8 #54).")
public class BreakGlassReviewRequestDTO {

    @NotNull
    @Schema(description = "The reviewer's decision.", example = "JUSTIFIED")
    private BreakGlassReviewOutcome outcome;

    @Size(max = 1024)
    @Schema(description = "Optional note for the record (e.g. 'Confirmed with the on-call consultant').")
    private String note;
}
