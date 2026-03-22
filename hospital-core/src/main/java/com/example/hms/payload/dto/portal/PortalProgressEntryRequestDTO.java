package com.example.hms.payload.dto.portal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalProgressEntryRequestDTO {

    @NotNull
    private LocalDate progressDate;

    private String progressNote;

    @Min(1) @Max(10)
    private Integer selfRating;

    private Boolean onTrack;
}
