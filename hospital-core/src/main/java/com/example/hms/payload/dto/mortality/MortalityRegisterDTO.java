package com.example.hms.payload.dto.mortality;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * The mortality register for a period, with the two counts this product is
 * measured on broken out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MortalityRegisterDTO {

    private LocalDate from;
    private LocalDate to;
    private int totalDeaths;
    /** WHO definition — LATE_MATERNAL deaths are excluded and counted separately. */
    private int maternalDeaths;
    private int lateMaternalDeaths;
    private int perinatalDeaths;
    private int stillbirths;
    private List<DeathRecordResponseDTO> deaths;
}
