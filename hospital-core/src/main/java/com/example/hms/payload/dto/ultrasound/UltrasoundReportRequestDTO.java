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

    @NotNull(message = "{ultrasoundReport.scanDate.required}")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate scanDate;

    /**
     * Required, but checked by the service after the findings
     * ({@code UltrasoundServiceImpl.requireFindingCategory}, QA D1525): a
     * report that states nothing is refused as empty first, then the category
     * is asked for. Nothing pre-selects it.
     */
    private UltrasoundFindingCategory findingCategory;

    // Report finalization
    private Boolean reportFinalized;

    private String providerReviewNotes;
}
