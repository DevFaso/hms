package com.example.hms.payload.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalProgressEntryDTO {

    private UUID id;
    private UUID treatmentPlanId;
    private LocalDate progressDate;
    private String progressNote;
    private Integer selfRating;
    private Boolean onTrack;
    private LocalDateTime createdAt;
}
