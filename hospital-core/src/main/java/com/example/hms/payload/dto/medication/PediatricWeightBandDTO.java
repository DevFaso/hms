package com.example.hms.payload.dto.medication;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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

import java.math.BigDecimal;

/** Round 18 (QA D935): one paediatric weight band; min inclusive, max exclusive (null: no upper bound). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PediatricWeightBandDTO {

    @NotNull
    @DecimalMin(value = "0.0")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal minWeightKg;

    @DecimalMin(value = "0.01")
    @Digits(integer = 3, fraction = 2)
    private BigDecimal maxWeightKg;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 5, fraction = 3)
    private BigDecimal dosePerAdministration;

    @NotBlank
    @Size(max = 40)
    private String doseUnit;

    @NotNull
    @Min(1)
    @Max(24)
    private Integer maxDosesPerDay;
}
