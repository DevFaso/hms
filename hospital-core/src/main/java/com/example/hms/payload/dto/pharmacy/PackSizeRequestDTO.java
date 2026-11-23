package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * QA D2674 (r65): {@code PUT /pharmacy/inventory/items/{id}/pack-size}. Stock kept in packs is counted from now on
 * in {@code unit} ("comprimé"), {@code unitsPerPack} to a pack ("plaquette"). Done once per item.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackSizeRequestDTO {

    @NotNull
    @Min(2)
    @Max(10000)
    private Integer unitsPerPack;

    /** The unit the stock is counted in from now on, the one prescriptions are written in ("comprimé"). */
    @NotBlank
    @Size(max = 60)
    private String unit;

    /** The pack's own name ("plaquette", "boîte"); the item's current unit when left out. */
    @Size(max = 60)
    private String packUnit;

    @NotBlank
    @Size(max = 300)
    private String reason;

    /**
     * QA D3400 (r70): true when the stock, the reorder threshold and the order quantity are ALREADY counted in
     * {@code unit} (the pack size is only declared, nothing is multiplied); false when they are counted in packs and
     * are converted. Left out: already in units exactly when {@code unit} is the item's current unit.
     */
    private Boolean alreadyInUnits;
}
