package com.example.hms.payload.dto.imaging;

import com.example.hms.enums.ImagingModality;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D3450 / D3254 (r70, V361): one exam of the hospital's imaging catalogue (an active IMAGING line of its price
 * list), as the order form and the catalogue screen read it. No price: who orders an exam does not need it.
 *
 * @param modality                 from the exam's price-list link ({@code <MODALITY>:<study>}); null when unlinked
 * @param mayRevealPregnancy       what the hospital admin or a radiologist decided: true (never shown to a family
 *                                 proxy), false (cleared), null (nobody decided)
 * @param namedAsPregnancyStudy    what an undecided exam is taken for: its name or region names an obstetric,
 *                                 pelvic or gynaecological study
 * @param pregnancyFlagDecidedBy   who decided, as displayed; null while nobody has
 */
public record ImagingCatalogueExamDTO(
    UUID id,
    String code,
    String label,
    ImagingModality modality,
    String bodyRegion,
    Boolean mayRevealPregnancy,
    boolean namedAsPregnancyStudy,
    String pregnancyFlagDecidedBy,
    LocalDateTime pregnancyFlagDecidedAt,
    boolean canEdit) {
}
