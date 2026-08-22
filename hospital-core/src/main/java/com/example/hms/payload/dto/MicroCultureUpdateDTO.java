package com.example.hms.payload.dto;

import com.example.hms.enums.MicroGrowthResult;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Culture-level field update (P3 #19). After the report is FINAL,
 * {@code correctionReason} becomes mandatory and the report moves to
 * CORRECTED — it never silently reverts to editable.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MicroCultureUpdateDTO {

    @Size(max = 100)
    private String specimenSource;

    @PastOrPresent
    private LocalDateTime collectedAt;

    @Size(max = 255)
    private String gramStain;

    private MicroGrowthResult growthResult;

    @Size(max = 1000)
    private String notes;

    @Size(max = 500)
    private String correctionReason;
}
