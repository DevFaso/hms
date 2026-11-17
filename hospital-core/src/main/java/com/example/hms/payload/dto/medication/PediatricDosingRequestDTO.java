package com.example.hms.payload.dto.medication;

import com.example.hms.enums.PediatricDoseLimitStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Round 18 (QA D935): the paediatric dose limits of one catalogue item,
 * written by the hospital's pharmacy
 * ({@code PUT /medication-catalog/{id}/pediatric-dosing}). Every limit is
 * optional; a null limit is not checked. The status says whether these are a
 * published default awaiting local approval or values this pharmacy approved.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PediatricDosingRequestDTO {

    /** Usual maximum per dose, mg/kg (soft stop). */
    @DecimalMin(value = "0.001")
    @Digits(integer = 5, fraction = 3)
    private BigDecimal pediatricMaxDoseMgPerKg;

    /** Usual maximum per day, mg/kg/day (soft stop). */
    @DecimalMin(value = "0.001")
    @Digits(integer = 5, fraction = 3)
    private BigDecimal pediatricMaxDailyMgPerKg;

    /** Absolute maximum per dose, mg (soft stop). */
    @DecimalMin(value = "0.001")
    @Digits(integer = 7, fraction = 3)
    private BigDecimal pediatricMaxSingleDoseMg;

    /** Absolute maximum per day, mg (HARD stop: never overridable). */
    @DecimalMin(value = "0.001")
    @Digits(integer = 7, fraction = 3)
    private BigDecimal pediatricMaxDailyMg;

    /** No automatic mg/kg verdict below this age in months; null means 3 months. */
    @Min(0)
    @Max(216)
    private Integer pediatricManualReviewUnderMonths;

    @Valid
    @Size(max = 12)
    @Builder.Default
    private List<PediatricWeightBandDTO> pediatricWeightBands = new ArrayList<>();

    @Size(max = 500)
    private String pediatricDoseSource;

    @NotNull(message = "{medicationCatalogItem.pediatricDoseStatus.required}")
    private PediatricDoseLimitStatus pediatricDoseStatus;
}
