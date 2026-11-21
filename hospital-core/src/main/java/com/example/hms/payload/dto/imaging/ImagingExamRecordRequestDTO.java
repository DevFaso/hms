package com.example.hms.payload.dto.imaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Policy rule 3 (V346, provisional): how an imaging exam went, as whoever performed it records
 * it. Replaces the record: a field left out is cleared, and a record with nothing in it is refused
 * on the note ({@link #isSomethingRecorded}). Who recorded it and when come from the session, never
 * from here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The exam record of an imaging order: technical note, contrast given and radiation dose.")
public class ImagingExamRecordRequestDTO {

    @Size(max = 2000, message = "{imaging.exam.technicalNote.size}")
    @Schema(description = "Technical note: protocol used, positioning, incidents")
    private String technicalNote;

    @Schema(description = "Whether contrast was given")
    private Boolean contrastAdministered;

    @Size(max = 1000, message = "{imaging.exam.contrastDetails.size}")
    @Schema(description = "The contrast given: agent, volume, route, any reaction")
    private String contrastDetails;

    @DecimalMin(value = "0.0", message = "{imagingReport.radiationDoseMgy.min}")
    @Digits(integer = 5, fraction = 3, message = "{imaging.exam.radiationDose.digits}")
    @Schema(description = "Radiation dose in mGy")
    private BigDecimal radiationDoseMgy;

    /** An exam record says something: a note, whether contrast was given, its details or the dose. */
    @com.example.hms.validation.ReportedOn("technicalNote")
    @jakarta.validation.constraints.AssertTrue(message = "{imaging.exam.record.empty}")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    public boolean isSomethingRecorded() {
        boolean note = technicalNote != null && !technicalNote.isBlank();
        boolean details = contrastDetails != null && !contrastDetails.isBlank();
        return note || details || contrastAdministered != null || radiationDoseMgy != null;
    }
}
