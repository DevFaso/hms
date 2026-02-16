package com.example.hms.payload.dto.ultrasound;

import com.example.hms.enums.UltrasoundFindingCategory;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * Request DTO for creating or updating an ultrasound report.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UltrasoundReportRequestDTO extends UltrasoundReportBaseDTO {

    @NotNull(message = "Scan date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scanDate;

    @NotNull(message = "Finding category is required")
    private UltrasoundFindingCategory findingCategory;

    // Report finalization
    private Boolean reportFinalized;

    private String providerReviewNotes;
}
