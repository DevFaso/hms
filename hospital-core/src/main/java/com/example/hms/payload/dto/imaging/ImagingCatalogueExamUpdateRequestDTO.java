package com.example.hms.payload.dto.imaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * QA D3450 (r70, V361): what the hospital admin or a radiologist sets on an exam of the imaging catalogue: the
 * answer to « may reveal a pregnancy », and the body region. The exam's code, name and price belong to the price
 * list.
 *
 * <p>The answer is required, and « not decided » is one of its values: an empty body changes nothing by accident
 * (a flagged exam is never un-flagged by a request that forgot the field). The body region is written as sent; a
 * blank one clears it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagingCatalogueExamUpdateRequestDTO {

    /** The three answers to « may reveal a pregnancy ». */
    public enum PregnancyFlag {
        /** Nobody decided: the exam is read by its name, and held back for a patient who may be pregnant. */
        UNDECIDED,
        /** Never shown to a family proxy. */
        YES,
        /** Cleared: shown to a family proxy. */
        NO
    }

    @NotNull(message = "{imaging.catalogue.pregnancyFlag.required}")
    private PregnancyFlag pregnancyFlag;

    @Size(max = 150, message = "{imaging.catalogue.bodyRegion.size}")
    private String bodyRegion;
}
