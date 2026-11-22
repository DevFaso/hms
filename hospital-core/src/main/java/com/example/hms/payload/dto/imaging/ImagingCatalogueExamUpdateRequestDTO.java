package com.example.hms.payload.dto.imaging;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D3450 (r70, V361): what the hospital admin or a radiologist sets on an exam of the imaging catalogue. Both
 * fields are written as sent: a null {@link #mayRevealPregnancy} puts the exam back to « not decided », a blank
 * body region clears it. The exam's code, name and price belong to the price list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingCatalogueExamUpdateRequestDTO {

    /** True: never shown to a family proxy. False: cleared. Null: not decided. */
    private Boolean mayRevealPregnancy;

    @Size(max = 150, message = "{imaging.catalogue.bodyRegion.size}")
    private String bodyRegion;
}
